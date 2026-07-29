package io.github.opspilot.core.application.incident;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Runs one authoritative, durable Supervisor delegation decision at a time. */
public final class SupervisorOrchestrationService {
    public static final String NO_PROGRESS = "NO_PROGRESS";
    public static final String CODE_REVISION_UNRESOLVED = "CODE_REVISION_UNRESOLVED";

    private final DelegationStore store;
    private final DelegationGateway gateway;

    public SupervisorOrchestrationService(DelegationStore store, DelegationGateway gateway) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public Decision dispatchNext(RunSnapshot run) {
        Objects.requireNonNull(run, "run");
        if (run.activeAttempt()) {
            return new Decision(DecisionKind.WAIT, null, "ACTIVE_ATTEMPT");
        }
        if (run.consecutiveNoProgress() >= run.maxNoProgress()) {
            store.recordMissingEvidence(run.runId(), NO_PROGRESS);
            return new Decision(DecisionKind.LIMITED_REPORT, null, NO_PROGRESS);
        }

        int ordinal = run.completedOrSkippedSteps();
        List<PlanStep> plan = run.plan();
        while (ordinal < plan.size() && shouldSkip(plan.get(ordinal), run)) {
            store.markSkipped(run.runId(), plan.get(ordinal).stepId(), CODE_REVISION_UNRESOLVED);
            ordinal++;
        }
        if (ordinal >= plan.size()) {
            return new Decision(DecisionKind.PLAN_COMPLETE, null, "PLAN_COMPLETE");
        }

        PlanStep step = plan.get(ordinal);
        assertExpectedState(step.role(), run.state());
        DelegationRecord record = new DelegationRecord(
                run.runId(), step.stepId(), run.nextAttempt(), UUID.randomUUID().toString(),
                step.role().agentId, step.role().skill, run.evidenceIds(), run.artifactIds(),
                run.remainingBudget(), run.deadline(), run.capabilitySnapshot(), Instant.now());
        store.commitBeforeNetwork(record);
        gateway.send(record);
        return new Decision(DecisionKind.DELEGATED, record, "STEP_DELEGATED");
    }

    public static ProfessionalResult interpretProfessionalResult(
            Role caller, ResultKind result, String technicalFailureCode) {
        if (caller != Role.SUPERVISOR) {
            throw new IllegalArgumentException("PROFESSIONAL_PEER_DELEGATION_FORBIDDEN");
        }
        return switch (Objects.requireNonNull(result, "result")) {
            case KB_EMPTY, NO_MATCH -> new ProfessionalResult(true, false, result.name(), List.of());
            case EVIDENCE_ADDED -> new ProfessionalResult(true, true, result.name(), List.of());
            case TECHNICAL_FAILURE -> {
                if (technicalFailureCode == null || technicalFailureCode.isBlank()) {
                    throw new IllegalArgumentException("TECHNICAL_FAILURE_CODE_REQUIRED");
                }
                yield new ProfessionalResult(false, false, technicalFailureCode,
                        List.of("technical:" + technicalFailureCode));
            }
        };
    }

    private static boolean shouldSkip(PlanStep step, RunSnapshot run) {
        return step.role() == Role.CODE_ANALYSIS && !run.applicableCodeRevision();
    }

    private static void assertExpectedState(Role role, IncidentRunState state) {
        IncidentRunState expected = switch (role) {
            case EVIDENCE_COLLECTOR -> IncidentRunState.COLLECTING_EVIDENCE;
            case CODE_ANALYSIS -> IncidentRunState.ANALYZING_CODE;
            case KNOWLEDGE -> IncidentRunState.RETRIEVING_KNOWLEDGE;
            case DIAGNOSIS -> IncidentRunState.GENERATING_HYPOTHESES;
            case REMEDIATION -> IncidentRunState.GENERATING_REMEDIATION;
            case SUPERVISOR -> throw new IllegalArgumentException("SUPERVISOR_CANNOT_DELEGATE_TO_SELF");
        };
        if (state != expected) {
            throw new IllegalStateException("AUTHORITATIVE_STATE_MISMATCH");
        }
    }

    public interface DelegationStore {
        /** This method must return only after its transaction commits. */
        void commitBeforeNetwork(DelegationRecord record);

        void markSkipped(RunId runId, StepId stepId, String reasonCode);

        void recordMissingEvidence(RunId runId, String reasonCode);
    }

    @FunctionalInterface
    public interface DelegationGateway {
        void send(DelegationRecord record);
    }

    public enum Role {
        SUPERVISOR("supervisor", "incident-investigation"),
        EVIDENCE_COLLECTOR("evidence-agent", "collect-runtime-evidence"),
        CODE_ANALYSIS("code-agent", "analyze-code"),
        KNOWLEDGE("knowledge-agent", "retrieve-incident-knowledge"),
        DIAGNOSIS("diagnosis-agent", "diagnose-incident"),
        REMEDIATION("remediation-agent", "propose-remediation");

        private final String agentId;
        private final String skill;

        Role(String agentId, String skill) {
            this.agentId = agentId;
            this.skill = skill;
        }
    }

    public enum ResultKind { EVIDENCE_ADDED, KB_EMPTY, NO_MATCH, TECHNICAL_FAILURE }

    public enum DecisionKind { DELEGATED, WAIT, PLAN_COMPLETE, LIMITED_REPORT }

    public record PlanStep(StepId stepId, Role role) {
        public PlanStep { Objects.requireNonNull(stepId, "stepId"); Objects.requireNonNull(role, "role"); }
    }

    public record RemainingBudget(int rounds, int agentCalls, int a2aCalls, long tokens, long costMicros) {
        public RemainingBudget {
            if (rounds < 0 || agentCalls < 0 || a2aCalls < 0 || tokens < 0 || costMicros < 0) {
                throw new IllegalArgumentException("remaining budget must not be negative");
            }
        }
    }

    public record RunSnapshot(
            RunId runId, IncidentRunState state, List<PlanStep> plan, int completedOrSkippedSteps,
            int nextAttempt, boolean activeAttempt, boolean applicableCodeRevision,
            Set<String> evidenceFingerprints, int consecutiveNoProgress, int maxNoProgress,
            List<EvidenceId> evidenceIds, List<ArtifactId> artifactIds,
            RemainingBudget remainingBudget, Instant deadline, String capabilitySnapshot) {
        public RunSnapshot {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(state, "state");
            plan = List.copyOf(plan);
            evidenceFingerprints = Set.copyOf(evidenceFingerprints);
            evidenceIds = List.copyOf(evidenceIds);
            artifactIds = List.copyOf(artifactIds);
            Objects.requireNonNull(remainingBudget, "remainingBudget");
            Objects.requireNonNull(deadline, "deadline");
            if (completedOrSkippedSteps < 0 || completedOrSkippedSteps > plan.size()
                    || nextAttempt < 1 || consecutiveNoProgress < 0 || maxNoProgress < 1
                    || capabilitySnapshot == null || capabilitySnapshot.isBlank()) {
                throw new IllegalArgumentException("invalid orchestration snapshot");
            }
        }
    }

    public record DelegationRecord(
            RunId runId, StepId stepId, int attempt, String messageId,
            String targetAgent, String targetSkill, List<EvidenceId> evidenceIds,
            List<ArtifactId> artifactIds, RemainingBudget remainingBudget,
            Instant deadline, String capabilitySnapshot, Instant createdAt) {
        public DelegationRecord {
            evidenceIds = List.copyOf(evidenceIds);
            artifactIds = List.copyOf(artifactIds);
        }
    }

    public record Decision(DecisionKind kind, DelegationRecord record, String reasonCode) { }

    public record ProfessionalResult(
            boolean succeeded, boolean newEvidence, String outcomeCode, List<String> missingEvidence) {
        public ProfessionalResult { missingEvidence = List.copyOf(missingEvidence); }
    }
}
