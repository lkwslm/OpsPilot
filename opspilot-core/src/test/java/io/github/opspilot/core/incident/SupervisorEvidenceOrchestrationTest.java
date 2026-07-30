package io.github.opspilot.core.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.application.evidence.AnalysisSealService;
import io.github.opspilot.core.application.evidence.ArtifactReceiver;
import io.github.opspilot.core.application.incident.RcaReportService;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.opspilot.core.application.evidence.ArtifactReceiver.Layer;
import static io.github.opspilot.core.application.evidence.ArtifactReceiver.RawFactKind;
import static io.github.opspilot.core.application.incident.SupervisorOrchestrationService.Role;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorEvidenceOrchestrationTest {
    private static final Instant DEADLINE = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void delegationIsSequentialAndCommittedBeforeNetwork() {
        List<String> order = new ArrayList<>();
        var service = new SupervisorOrchestrationService(
                new Store(order, false), record -> order.add("network:" + record.targetSkill()));

        var decision = service.dispatchNext(snapshot(Role.EVIDENCE_COLLECTOR, true, 0, false));

        assertEquals(List.of("commit:collect-runtime-evidence", "network:collect-runtime-evidence"), order);
        assertEquals(SupervisorOrchestrationService.DecisionKind.DELEGATED, decision.kind());
        assertEquals(1, decision.record().attempt());
        assertFalse(decision.record().capabilitySnapshot().isBlank());
        assertEquals(1, decision.record().evidenceIds().size());
        assertEquals(1, decision.record().artifactIds().size());
    }

    @Test
    void failedCheckpointNeverTouchesNetwork() {
        AtomicInteger networkCalls = new AtomicInteger();
        var service = new SupervisorOrchestrationService(
                new Store(new ArrayList<>(), true), ignored -> networkCalls.incrementAndGet());

        assertThrows(IllegalStateException.class,
                () -> service.dispatchNext(snapshot(Role.EVIDENCE_COLLECTOR, true, 0, false)));
        assertEquals(0, networkCalls.get());
    }

    @Test
    void codeWithoutRevisionIsExplicitlySkippedAndNextStepRemainsOrdered() {
        List<String> order = new ArrayList<>();
        RunId runId = new RunId(UUID.randomUUID());
        List<SupervisorOrchestrationService.PlanStep> plan = List.of(
                new SupervisorOrchestrationService.PlanStep(new StepId(UUID.randomUUID()), Role.CODE_ANALYSIS),
                new SupervisorOrchestrationService.PlanStep(new StepId(UUID.randomUUID()), Role.KNOWLEDGE));
        var run = snapshot(runId, IncidentRunState.RETRIEVING_KNOWLEDGE, plan, false, 0, false);
        var service = new SupervisorOrchestrationService(new Store(order, false),
                record -> order.add("network:" + record.targetSkill()));

        service.dispatchNext(run);

        assertEquals(List.of("skip:CODE_REVISION_UNRESOLVED", "commit:retrieve-incident-knowledge",
                "network:retrieve-incident-knowledge"), order);
    }

    @Test
    void noProgressStopsBeforeCreatingAnotherAttempt() {
        List<String> order = new ArrayList<>();
        var service = new SupervisorOrchestrationService(new Store(order, false), ignored -> order.add("network"));
        var decision = service.dispatchNext(snapshot(Role.DIAGNOSIS, true, 2, false));
        assertEquals(SupervisorOrchestrationService.DecisionKind.LIMITED_REPORT, decision.kind());
        assertEquals(List.of("missing:NO_PROGRESS"), order);
    }

    @Test
    void professionalsCannotDelegateAndKnowledgeEmptyDiffersFromTechnicalFailure() {
        assertThrows(IllegalArgumentException.class, () ->
                SupervisorOrchestrationService.interpretProfessionalResult(
                        Role.DIAGNOSIS, SupervisorOrchestrationService.ResultKind.NO_MATCH, null));
        var empty = SupervisorOrchestrationService.interpretProfessionalResult(
                Role.SUPERVISOR, SupervisorOrchestrationService.ResultKind.KB_EMPTY, null);
        var failed = SupervisorOrchestrationService.interpretProfessionalResult(
                Role.SUPERVISOR, SupervisorOrchestrationService.ResultKind.TECHNICAL_FAILURE, "RERANK_TIMEOUT");
        assertTrue(empty.succeeded());
        assertFalse(empty.newEvidence());
        assertFalse(failed.succeeded());
        assertEquals("RERANK_TIMEOUT", failed.outcomeCode());
        assertEquals(List.of("technical:RERANK_TIMEOUT"), failed.missingEvidence());
    }

    @Test
    void eachArtifactValidationLayerFailsAtItsOwnStableBoundary() {
        for (Layer layer : Layer.values()) {
            OrderedValidation validation = new OrderedValidation(layer);
            AtomicInteger writes = new AtomicInteger();
            ArtifactReceiver receiver = new ArtifactReceiver(validation, ignored -> writes.incrementAndGet());
            ArtifactReceiver.RemoteArtifact artifact = invalidAt(layer, validArtifact());

            ArtifactReceiver.ValidationFailure failure = assertThrows(
                    ArtifactReceiver.ValidationFailure.class, () -> receiver.receive(artifact));

            assertEquals(layer, failure.layer());
            assertEquals(0, writes.get());
            assertEquals(validation.expectedCallsThrough(layer), validation.calls);
        }
    }

    @Test
    void receptionCommitsAllFactsOnceAndRejectsRawOrCrossRunAnalysis() {
        AtomicInteger commits = new AtomicInteger();
        ArtifactReceiver receiver = new ArtifactReceiver(new OrderedValidation(null), ignored -> commits.incrementAndGet());
        ArtifactReceiver.RemoteArtifact valid = validArtifact();
        receiver.receive(valid);
        assertEquals(1, commits.get());

        var mutation = valid.mutation();
        var raw = withMutation(valid, new ArtifactReceiver.ReceptionMutation(
                mutation.artifactId(), mutation.runId(), mutation.evidence(), mutation.hypotheses(),
                mutation.relations(), mutation.verifications(), List.of(RawFactKind.CODE_FINDING),
                mutation.outboxEventId()));
        assertEquals("UNNORMALIZED_FACT_FORBIDDEN",
                assertThrows(ArtifactReceiver.ValidationFailure.class, () -> receiver.receive(raw)).errorCode());

        var otherEvidence = new EvidenceId(UUID.randomUUID());
        var wrongHypothesis = new ArtifactReceiver.HypothesisWrite(
                new HypothesisId(UUID.randomUUID()), "wrong", List.of(otherEvidence));
        var crossRun = withMutation(valid, new ArtifactReceiver.ReceptionMutation(
                mutation.artifactId(), mutation.runId(), mutation.evidence(), List.of(wrongHypothesis),
                List.of(), List.of(), List.of(RawFactKind.EVIDENCE), mutation.outboxEventId()));
        assertEquals("ANALYSIS_EVIDENCE_WRONG_RUN",
                assertThrows(ArtifactReceiver.ValidationFailure.class, () -> receiver.receive(crossRun)).errorCode());
        assertEquals(1, commits.get());
    }

    @Test
    void unitOfWorkFailureIsVisibleAndSafeToRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        ArtifactReceiver receiver = new ArtifactReceiver(new OrderedValidation(null), mutation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("OUTBOX_INSERT_FAILED");
            }
            commits.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> receiver.receive(validArtifact()));
        assertEquals(0, commits.get());
        receiver.receive(validArtifact());
        assertEquals(1, commits.get());
    }

    @Test
    void sealRequiresTerminalOrMissingAttemptsAndAdvancesVersionOnce() {
        RunId runId = new RunId(UUID.randomUUID());
        var repository = new SealStore(new AnalysisSealService.SealReadiness(List.of(
                new AnalysisSealService.AttemptReadiness("a1", true, null),
                new AnalysisSealService.AttemptReadiness("a2", false, "LOG_WINDOW_UNAVAILABLE")), 0));
        var sealed = new AnalysisSealService(repository).seal(runId, 7, DEADLINE);
        assertEquals(8, sealed.runVersion());
        assertEquals(List.of("LOG_WINDOW_UNAVAILABLE"), sealed.missingEvidence());
        assertThrows(AnalysisSealService.SealConflict.class,
                () -> new AnalysisSealService(repository).seal(runId, 7, DEADLINE));
    }

    @Test
    void pendingArtifactOrUnreconciledStreamPreventsSeal() {
        RunId runId = new RunId(UUID.randomUUID());
        var pendingArtifact = new SealStore(new AnalysisSealService.SealReadiness(List.of(), 1));
        var activeStream = new SealStore(new AnalysisSealService.SealReadiness(List.of(
                new AnalysisSealService.AttemptReadiness("stream", false, null)), 0));
        assertEquals("ARTIFACT_RECEPTION_PENDING", assertThrows(AnalysisSealService.SealConflict.class,
                () -> new AnalysisSealService(pendingArtifact).seal(runId, 0, DEADLINE)).getMessage());
        assertEquals("ATTEMPT_NOT_RECONCILED", assertThrows(AnalysisSealService.SealConflict.class,
                () -> new AnalysisSealService(activeStream).seal(runId, 0, DEADLINE)).getMessage());
    }

    @Test
    void reportUsesOneSealedReadAndCallsModelOutsideTransaction() {
        RunId runId = new RunId(UUID.randomUUID());
        IncidentId incidentId = new IncidentId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        ArtifactId artifactId = new ArtifactId(UUID.randomUUID());
        HypothesisId hypothesisId = new HypothesisId(UUID.randomUUID());
        AtomicBoolean inTransaction = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        RcaReportService.SealedAnalysis input = new RcaReportService.SealedAnalysis(
                incidentId, runId, 4, "GENERATING_REPORT", DEADLINE,
                DEADLINE.minusSeconds(60), DEADLINE.plusSeconds(60),
                List.of(new RcaReportService.EvidenceView(evidenceId, "db.pool.wait.high", "fact",
                        artifactId, "a".repeat(64), true, DEADLINE)),
                List.of(new RcaReportService.HypothesisView(hypothesisId, "pool saturated", "SUPPORTED", 0.9)),
                List.of(new RcaReportService.RelationView(hypothesisId, evidenceId, "SUPPORTS")),
                List.of(new RcaReportService.VerificationView(hypothesisId, evidenceId, "CONFIRMED", "check")),
                List.of("trace unavailable"));
        var service = new RcaReportService((id, version) -> {
            inTransaction.set(true);
            reads.incrementAndGet();
            inTransaction.set(false);
            return input;
        }, sealed -> {
            assertFalse(inTransaction.get());
            return validRca(input, evidenceId, artifactId, hypothesisId);
        }, new ObjectMapper().findAndRegisterModules());

        var first = service.generate(runId, 4);
        var retry = service.generate(runId, 4);

        assertEquals(first.inputDigest(), retry.inputDigest());
        assertEquals(2, reads.get());
        assertSame(first.rca(), first.rca());
        assertTrue(first.jsonArtifact().contains("database.pool_exhausted"));
        assertTrue(first.markdownArtifact().contains("database.pool_exhausted"));
        assertFalse(first.jsonArtifact().contains("markdown"));
    }

    @Test
    void reportRejectsInventedEvidenceAndMissingRootCauseCode() {
        RunId runId = new RunId(UUID.randomUUID());
        IncidentId incidentId = new IncidentId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        ArtifactId artifactId = new ArtifactId(UUID.randomUUID());
        HypothesisId hypothesisId = new HypothesisId(UUID.randomUUID());
        var input = new RcaReportService.SealedAnalysis(
                incidentId, runId, 1, "GENERATING_REPORT", DEADLINE,
                DEADLINE.minusSeconds(60), DEADLINE.plusSeconds(60),
                List.of(new RcaReportService.EvidenceView(evidenceId, "db.pool.wait.high", "fact",
                        artifactId, "b".repeat(64), true, DEADLINE)),
                List.of(new RcaReportService.HypothesisView(hypothesisId, "cause", "SUPPORTED", 0.8)),
                List.of(), List.of(), List.of());
        var invented = new RcaReportService((id, version) -> input,
                ignored -> {
                    var valid = validRca(input, evidenceId, artifactId, hypothesisId);
                    return new RcaReportService.StructuredRca(
                            valid.schemaVersion(), valid.incidentId(), valid.runId(), valid.summary(),
                            valid.severity(), valid.outcome(), valid.rootCause(), valid.evidenceAssessment(),
                            valid.hypotheses(), valid.actions(), List.of(new RcaReportService.Citation(
                                    "root", UUID.randomUUID().toString(), "db.pool.wait.high",
                                    artifactId.wire())), valid.limitations(), valid.generatedAt());
                }, new ObjectMapper().findAndRegisterModules());
        var noCode = new RcaReportService((id, version) -> input,
                ignored -> {
                    var valid = validRca(input, evidenceId, artifactId, hypothesisId);
                    return new RcaReportService.StructuredRca(
                            valid.schemaVersion(), valid.incidentId(), valid.runId(), valid.summary(),
                            valid.severity(), valid.outcome(), new RcaReportService.RootCause(
                                    "", "cause", "order", 0.8, List.of(evidenceId.wire()), List.of()),
                            valid.evidenceAssessment(), valid.hypotheses(), valid.actions(), valid.citations(),
                            valid.limitations(), valid.generatedAt());
                }, new ObjectMapper().findAndRegisterModules());
        assertEquals("RCA_CITATION_INVALID",
                assertThrows(IllegalArgumentException.class, () -> invented.generate(runId, 1)).getMessage());
        assertEquals("RCA_ROOT_CAUSE_CODE_INVALID",
                assertThrows(IllegalArgumentException.class, () -> noCode.generate(runId, 1)).getMessage());
    }

    @Test
    void insufficientEvidenceAllowsInconclusiveWithoutInventingRootCause() {
        RunId runId = new RunId(UUID.randomUUID());
        IncidentId incidentId = new IncidentId(UUID.randomUUID());
        HypothesisId hypothesisId = new HypothesisId(UUID.randomUUID());
        var input = new RcaReportService.SealedAnalysis(
                incidentId, runId, 2, "GENERATING_REPORT", DEADLINE,
                DEADLINE.minusSeconds(60), DEADLINE.plusSeconds(60), List.of(),
                List.of(new RcaReportService.HypothesisView(hypothesisId, "unknown", "UNVERIFIED", 0.1)),
                List.of(), List.of(), List.of("trace.unavailable"));
        var service = new RcaReportService((id, version) -> input, ignored ->
                new RcaReportService.StructuredRca(
                        "1.0.0", incidentId.wire(), runId.wire(), "insufficient evidence",
                        "HIGH", "INCONCLUSIVE", null,
                        new RcaReportService.EvidenceAssessment(0, List.of("trace.unavailable"), List.of("trace")),
                        List.of(new RcaReportService.HypothesisResult(
                                hypothesisId.wire(), "unknown", "UNVERIFIED", 0.1, List.of(), List.of())),
                        java.util.Map.of(
                                "immediate", List.of(), "longTerm", List.of(), "monitoring", List.of(),
                                "tests", List.of(), "humanNextSteps", List.of(), "rollback", List.of()),
                        List.of(), List.of("trace unavailable"), DEADLINE),
                new ObjectMapper().findAndRegisterModules());
        assertEquals("INCONCLUSIVE", service.generate(runId, 2).rca().outcome());
    }

    private static RcaReportService.StructuredRca validRca(
            RcaReportService.SealedAnalysis input, EvidenceId evidenceId,
            ArtifactId artifactId, HypothesisId hypothesisId) {
        return new RcaReportService.StructuredRca(
                "1.0.0", input.incidentId().wire(), input.runId().wire(), "pool saturated",
                "HIGH", "PARTIAL", new RcaReportService.RootCause(
                        "database.pool_exhausted", "pool saturated", "order", 0.9,
                        List.of(evidenceId.wire()), List.of()),
                new RcaReportService.EvidenceAssessment(0.8, input.missingEvidence(), List.of()),
                List.of(new RcaReportService.HypothesisResult(
                        hypothesisId.wire(), "pool saturated", "SUPPORTED", 0.9,
                        List.of(evidenceId.wire()), List.of())),
                java.util.Map.of(
                        "immediate", List.of(), "longTerm", List.of(), "monitoring", List.of(),
                        "tests", List.of(), "humanNextSteps", List.of(), "rollback", List.of()),
                List.of(new RcaReportService.Citation(
                        "root", evidenceId.wire(), "db.pool.wait.high", artifactId.wire())),
                List.of("trace unavailable"), DEADLINE);
    }

    private static SupervisorOrchestrationService.RunSnapshot snapshot(
            Role role, boolean codeRevision, int noProgress, boolean active) {
        RunId runId = new RunId(UUID.randomUUID());
        IncidentRunState state = switch (role) {
            case EVIDENCE_COLLECTOR -> IncidentRunState.COLLECTING_EVIDENCE;
            case CODE_ANALYSIS -> IncidentRunState.ANALYZING_CODE;
            case KNOWLEDGE -> IncidentRunState.RETRIEVING_KNOWLEDGE;
            case DIAGNOSIS -> IncidentRunState.GENERATING_HYPOTHESES;
            case REMEDIATION -> IncidentRunState.GENERATING_REMEDIATION;
            case SUPERVISOR -> IncidentRunState.PLANNING;
        };
        return snapshot(runId, state, List.of(new SupervisorOrchestrationService.PlanStep(
                new StepId(UUID.randomUUID()), role)), codeRevision, noProgress, active);
    }

    private static SupervisorOrchestrationService.RunSnapshot snapshot(
            RunId runId, IncidentRunState state, List<SupervisorOrchestrationService.PlanStep> plan,
            boolean codeRevision, int noProgress, boolean active) {
        return new SupervisorOrchestrationService.RunSnapshot(
                runId, state, plan, 0, 1, active, codeRevision, Set.of("existing"), noProgress, 2,
                List.of(new EvidenceId(UUID.randomUUID())), List.of(new ArtifactId(UUID.randomUUID())),
                new SupervisorOrchestrationService.RemainingBudget(2, 3, 4, 500, 600),
                DEADLINE, "capability-digest");
    }

    private static ArtifactReceiver.RemoteArtifact validArtifact() {
        RunId runId = new RunId(UUID.randomUUID());
        ArtifactId artifactId = new ArtifactId(UUID.randomUUID());
        EvidenceId evidenceId = new EvidenceId(UUID.randomUUID());
        HypothesisId hypothesisId = new HypothesisId(UUID.randomUUID());
        byte[] payload = "{\"schemaVersion\":\"1.0.0\"}".getBytes();
        var mutation = new ArtifactReceiver.ReceptionMutation(
                artifactId, runId, List.of(new ArtifactReceiver.EvidenceWrite(evidenceId, "fact")),
                List.of(new ArtifactReceiver.HypothesisWrite(hypothesisId, "cause", List.of(evidenceId))),
                List.of(new ArtifactReceiver.RelationWrite(hypothesisId, evidenceId, "SUPPORTS")),
                List.of(new ArtifactReceiver.VerificationWrite(
                        UUID.randomUUID(), hypothesisId, evidenceId, "CONFIRMED", "check")),
                List.of(RawFactKind.EVIDENCE), UUID.randomUUID());
        return ArtifactReceiver.RemoteArtifact.json(
                artifactId, runId, UUID.randomUUID(), payload, List.of(evidenceId), mutation);
    }

    private static ArtifactReceiver.RemoteArtifact invalidAt(
            Layer layer, ArtifactReceiver.RemoteArtifact value) {
        return switch (layer) {
            case MEDIA_TYPE -> copy(value, "text/plain", value.schemaVersion(), value.sha256());
            case SCHEMA_MAJOR -> copy(value, value.mediaType(), "2.0.0", value.sha256());
            case ARTIFACT_SHA256 -> copy(value, value.mediaType(), value.schemaVersion(), "0".repeat(64));
            default -> value;
        };
    }

    private static ArtifactReceiver.RemoteArtifact copy(
            ArtifactReceiver.RemoteArtifact value, String mediaType, String schemaVersion, String sha256) {
        return new ArtifactReceiver.RemoteArtifact(
                value.artifactId(), value.runId(), value.taskId(), value.sourceId(), value.resourceId(),
                mediaType, schemaVersion, sha256, value.payload(), value.currentRunEvidence(), value.mutation());
    }

    private static ArtifactReceiver.RemoteArtifact withMutation(
            ArtifactReceiver.RemoteArtifact value, ArtifactReceiver.ReceptionMutation mutation) {
        return new ArtifactReceiver.RemoteArtifact(
                value.artifactId(), value.runId(), value.taskId(), value.sourceId(), value.resourceId(),
                value.mediaType(), value.schemaVersion(), value.sha256(), value.payload(),
                value.currentRunEvidence(), mutation);
    }

    private static final class Store implements SupervisorOrchestrationService.DelegationStore {
        private final List<String> order;
        private final boolean fail;
        private Store(List<String> order, boolean fail) { this.order = order; this.fail = fail; }
        @Override public void commitBeforeNetwork(SupervisorOrchestrationService.DelegationRecord record) {
            if (fail) throw new IllegalStateException("CHECKPOINT_FAILED");
            order.add("commit:" + record.targetSkill());
        }
        @Override public void markSkipped(RunId runId, StepId stepId, String reasonCode) {
            order.add("skip:" + reasonCode);
        }
        @Override public void recordMissingEvidence(RunId runId, String reasonCode) {
            order.add("missing:" + reasonCode);
        }
    }

    private static final class OrderedValidation implements ArtifactReceiver.ValidationPort {
        private final Layer failure;
        private int calls;
        private OrderedValidation(Layer failure) { this.failure = failure; }
        @Override public boolean jsonSchemaValid(ArtifactReceiver.RemoteArtifact artifact) {
            calls++; return failure != Layer.JSON_SCHEMA;
        }
        @Override public boolean sourceOwnedByRun(ArtifactReceiver.RemoteArtifact artifact) {
            calls++; return failure != Layer.SOURCE_OWNERSHIP;
        }
        @Override public boolean resourceTaskRunOwned(ArtifactReceiver.RemoteArtifact artifact) {
            calls++; return failure != Layer.RESOURCE_TASK_RUN_OWNERSHIP;
        }
        @Override public boolean referencesAuthorized(ArtifactReceiver.RemoteArtifact artifact) {
            calls++; return failure != Layer.REFERENCE_PERMISSION;
        }
        @Override public boolean domainInvariantsValid(ArtifactReceiver.RemoteArtifact artifact) {
            calls++; return failure != Layer.DOMAIN_INVARIANT;
        }
        int expectedCallsThrough(Layer layer) {
            return switch (layer) {
                case MEDIA_TYPE, SCHEMA_MAJOR -> 0;
                case JSON_SCHEMA -> 1;
                case SOURCE_OWNERSHIP -> 2;
                case RESOURCE_TASK_RUN_OWNERSHIP, ARTIFACT_SHA256 -> 3;
                case REFERENCE_PERMISSION -> 4;
                case DOMAIN_INVARIANT -> 5;
            };
        }
    }

    private static final class SealStore implements AnalysisSealService.SealRepository {
        private final AnalysisSealService.SealReadiness readiness;
        private boolean sealed;
        private SealStore(AnalysisSealService.SealReadiness readiness) { this.readiness = readiness; }
        @Override public AnalysisSealService.SealReadiness readiness(RunId runId) { return readiness; }
        @Override public AnalysisSealService.SealedRun sealAtomically(
                RunId runId, long expectedVersion, Instant sealedAt, List<String> missingEvidence) {
            if (sealed) throw new AnalysisSealService.SealConflict("ANALYSIS_SEAL_CONFLICT");
            sealed = true;
            return new AnalysisSealService.SealedRun(runId, expectedVersion + 1, sealedAt, missingEvidence);
        }
    }
}
