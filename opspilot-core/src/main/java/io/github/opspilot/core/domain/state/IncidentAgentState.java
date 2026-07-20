package io.github.opspilot.core.domain.state;

import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Versioned, bounded checkpoint state. It contains references and summaries, never source payloads. */
public record IncidentAgentState(
        String schemaVersion,
        IncidentId incidentId,
        RunId runId,
        String a2aContextId,
        UUID supervisorAgentSessionId,
        long version,
        IncidentRunState status,
        InvestigationOutcome outcome,
        ArtifactId planArtifactId,
        long planVersion,
        StepId currentStepId,
        List<AgentStepSnapshot> steps,
        List<EvidenceId> evidenceIds,
        List<HypothesisId> hypothesisIds,
        ArtifactId remediationPlanArtifactId,
        UUID approvalId,
        TokenBudgetSnapshot tokenBudget,
        UsageStatistics usage,
        ReactLoopSnapshot reactLoop,
        List<String> missingEvidence,
        List<String> warnings,
        UUID failureId,
        ArtifactId finalReportArtifactId,
        boolean cancellationRequested,
        Instant deadline,
        Instant createdAt,
        Instant updatedAt) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0.0";

    public IncidentAgentState {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(a2aContextId, "a2aContextId");
        Objects.requireNonNull(supervisorAgentSessionId, "supervisorAgentSessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(tokenBudget, "tokenBudget");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(reactLoop, "reactLoop");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || planVersion < 0) {
            throw new IllegalArgumentException("snapshot versions must be non-negative");
        }
        steps = List.copyOf(steps);
        evidenceIds = List.copyOf(evidenceIds);
        hypothesisIds = List.copyOf(hypothesisIds);
        missingEvidence = List.copyOf(missingEvidence);
        warnings = List.copyOf(warnings);
    }

    public enum InvestigationOutcome { CONFIRMED, INCONCLUSIVE, NO_ISSUE_FOUND, CANCELLED }

    public record AgentStepSnapshot(
            StepId stepId,
            StepAttemptState status,
            List<StepAttemptSnapshot> attempts) {
        public AgentStepSnapshot {
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(status, "status");
            attempts = List.copyOf(attempts);
        }
    }

    public record StepAttemptSnapshot(
            Attempt attempt,
            A2aTaskId remoteTaskId,
            StepAttemptState status,
            long version,
            ArtifactId resultArtifactId) {
        public StepAttemptSnapshot {
            Objects.requireNonNull(attempt, "attempt");
            Objects.requireNonNull(status, "status");
            if (version < 0) {
                throw new IllegalArgumentException("attempt version must be non-negative");
            }
        }
    }

    public record TokenBudgetSnapshot(long limit, long consumed) {
        public TokenBudgetSnapshot {
            if (limit < 0 || consumed < 0 || consumed > limit) {
                throw new IllegalArgumentException("invalid token budget snapshot");
            }
        }
    }

    public record UsageStatistics(long inputTokens, long outputTokens, long costMicros) {
        public UsageStatistics {
            if (inputTokens < 0 || outputTokens < 0 || costMicros < 0) {
                throw new IllegalArgumentException("usage values must be non-negative");
            }
        }
    }

    public record ReactLoopSnapshot(
            int round,
            int agentCalls,
            int toolCalls,
            int a2aCalls,
            List<String> actionFingerprints,
            List<String> evidenceFingerprints) {
        public ReactLoopSnapshot {
            if (round < 0 || agentCalls < 0 || toolCalls < 0 || a2aCalls < 0) {
                throw new IllegalArgumentException("loop counters must be non-negative");
            }
            actionFingerprints = List.copyOf(actionFingerprints);
            evidenceFingerprints = List.copyOf(evidenceFingerprints);
        }
    }
}
