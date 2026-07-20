package io.github.opspilot.core.state;

import io.github.opspilot.core.application.state.IncidentAgentStateJson;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy.ReferenceRecord;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy.ReferenceType;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy.SnapshotLimits;
import io.github.opspilot.core.application.state.StateContractException;
import io.github.opspilot.core.application.state.StateMigrationChain;
import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.AgentStepSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.ReactLoopSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.StepAttemptSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.UsageStatistics;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
import io.github.opspilot.core.port.a2a.A2aTaskStatePort;
import io.github.opspilot.core.port.repository.IncidentAgentStateRepository;
import io.github.opspilot.core.port.runtime.AgentRuntimeStatePort;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.opspilot.core.application.state.IncidentAgentStatePolicy.STATE_CONTENT_FORBIDDEN;
import static io.github.opspilot.core.application.state.IncidentAgentStatePolicy.STATE_LIMIT_EXCEEDED;
import static io.github.opspilot.core.application.state.IncidentAgentStatePolicy.STATE_REFERENCE_INVALID;
import static io.github.opspilot.core.application.state.StateMigrationChain.STATE_SCHEMA_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotContractTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final IncidentId INCIDENT_ID = new IncidentId(uuid(1));
    private static final RunId RUN_ID = new RunId(uuid(2));
    private static final StepId STEP_ID = new StepId(uuid(3));
    private static final EvidenceId EVIDENCE_ID = new EvidenceId(uuid(4));
    private static final HypothesisId HYPOTHESIS_ID = new HypothesisId(uuid(5));
    private static final ArtifactId PLAN_ID = new ArtifactId(uuid(6));
    private static final ArtifactId RESULT_ID = new ArtifactId(uuid(7));

    @Test
    void everySupportedSchemaRoundTripsThroughRealJson() {
        IncidentAgentState current = sample(List.of("bounded warning"));
        IncidentAgentStateJson codec = codec(validReferences(), SnapshotLimits.defaults());

        byte[] currentJson = codec.write(current);
        assertEquals(current, codec.read(currentJson));

        String legacy = new String(currentJson, StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":\"1.0.0\"", "\"schemaVersion\":\"0.9.0\"")
                .replace(",\"cancellationRequested\":false", "");
        IncidentAgentState migrated = codec.read(legacy.getBytes(StandardCharsets.UTF_8));
        assertEquals(IncidentAgentState.CURRENT_SCHEMA_VERSION, migrated.schemaVersion());
        assertFalse(migrated.cancellationRequested());
        assertEquals(current.evidenceIds(), migrated.evidenceIds());
        assertEquals(current.steps(), migrated.steps());
    }

    @Test
    void limitsFailClosedWithoutChangingOriginalSnapshot() {
        IncidentAgentState original = sample(List.of("this warning is too long"));
        SnapshotLimits shortStrings = new SnapshotLimits(8, 256, 64, 128, 64, 262_144);

        StateContractException stringFailure = assertThrows(
                StateContractException.class, () -> codec(validReferences(), shortStrings).write(original));
        assertEquals(STATE_LIMIT_EXCEEDED, stringFailure.errorCode());
        assertEquals(0, original.version());
        assertEquals(List.of("this warning is too long"), original.warnings());

        SnapshotLimits tinyJson = new SnapshotLimits(512, 256, 64, 128, 64, 32);
        StateContractException sizeFailure = assertThrows(
                StateContractException.class, () -> codec(validReferences(), tinyJson).write(sample(List.of())));
        assertEquals(STATE_LIMIT_EXCEEDED, sizeFailure.errorCode());
    }

    @Test
    void unknownSchemaAndUnknownFieldsNeverContinueSilently() {
        IncidentAgentStateJson codec = codec(validReferences(), SnapshotLimits.defaults());
        String json = codec.writeString(sample(List.of()));

        StateContractException unknownVersion = assertThrows(StateContractException.class,
                () -> codec.read(json.replace("1.0.0", "77.0.0").getBytes(StandardCharsets.UTF_8)));
        assertEquals(STATE_SCHEMA_UNSUPPORTED, unknownVersion.errorCode());

        String unknownField = json.substring(0, json.length() - 1) + ",\"futureField\":1}";
        StateContractException droppedField = assertThrows(StateContractException.class,
                () -> codec.read(unknownField.getBytes(StandardCharsets.UTF_8)));
        assertEquals(IncidentAgentStateJson.STATE_JSON_INVALID, droppedField.errorCode());
    }

    @Test
    void missingCrossRunAndWrongTypeReferencesStopRecovery() {
        IncidentAgentState state = sample(List.of());
        RunId otherRun = new RunId(uuid(99));
        Map<String, IncidentAgentStatePolicy.ReferenceCatalog> invalidCatalogs = Map.of(
                "missing", ignored -> Optional.empty(),
                "cross-run", id -> Optional.of(new ReferenceRecord(id, otherRun, expectedType(id))),
                "wrong-type", id -> Optional.of(new ReferenceRecord(id, RUN_ID, ReferenceType.ARTIFACT)));

        for (var entry : invalidCatalogs.entrySet()) {
            StateContractException failure = assertThrows(StateContractException.class,
                    () -> codec(entry.getValue(), SnapshotLimits.defaults()).write(state), entry.getKey());
            assertEquals(STATE_REFERENCE_INVALID, failure.errorCode(), entry.getKey());
            assertEquals(IncidentRunState.COLLECTING_EVIDENCE, state.status());
        }
    }

    @Test
    void forbiddenPayloadKindsAreRejectedBeforePersistence() {
        for (String forbidden : List.of(
                "stack trace", "```java", "prompt:", "response:", "hidden reasoning",
                "message history", "tool raw output", "secret=abc", "ground truth",
                "full evidence", "artifact body", "source payload")) {
            StateContractException failure = assertThrows(StateContractException.class,
                    () -> codec(validReferences(), SnapshotLimits.defaults()).write(sample(List.of(forbidden))));
            assertEquals(STATE_CONTENT_FORBIDDEN, failure.errorCode(), forbidden);
        }
    }

    @Test
    void threeStatePlanesRecoverIndependentlyAndUseIndependentVersions() throws InterruptedException {
        IncidentMemory incident = new IncidentMemory();
        RuntimeMemory runtime = new RuntimeMemory();
        TaskMemory task = new TaskMemory();
        IncidentAgentState snapshot = sample(List.of());
        AgentRuntimeStatePort.RuntimeState runtimeState = new AgentRuntimeStatePort.RuntimeState(
                snapshot.supervisorAgentSessionId(), RUN_ID, 0, Map.of("runtime-key", "runtime-value"));
        RemoteTaskId taskId = new RemoteTaskId("code-agent", new A2aTaskId(uuid(8)));
        A2aTaskStatePort.TaskState taskState = new A2aTaskStatePort.TaskState(
                taskId, A2aTaskState.WORKING, 0, NOW);

        incident.save(snapshot, -1);
        runtime.store(runtimeState, -1);
        task.store(taskState, -1);
        assertEquals(snapshot, incident.load(RUN_ID).orElseThrow());
        assertEquals(runtimeState, runtime.load(snapshot.supervisorAgentSessionId()).orElseThrow());
        assertEquals(taskState, task.load(taskId).orElseThrow());
        assertNotEquals(incident.values.get(RUN_ID).getClass(), runtime.values.get(runtimeState.sessionId()).getClass());
        assertNotEquals(runtime.values.get(runtimeState.sessionId()).getClass(), task.values.get(taskId).getClass());

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        Runnable competingSave = () -> {
            try {
                start.await();
                incident.save(withVersion(snapshot, 1), 0);
                successes.incrementAndGet();
            } catch (OptimisticLockFailure ignored) {
                // Expected for exactly one contender; this is a test Port, not a Provider replacement.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = Thread.ofPlatform().start(competingSave);
        Thread second = Thread.ofPlatform().start(competingSave);
        start.countDown();
        first.join();
        second.join();

        assertEquals(1, successes.get());
        assertEquals(1, incident.load(RUN_ID).orElseThrow().version());
        assertEquals(0, runtime.load(runtimeState.sessionId()).orElseThrow().version());
        assertEquals(0, task.load(taskId).orElseThrow().version());
    }

    private static IncidentAgentStateJson codec(
            IncidentAgentStatePolicy.ReferenceCatalog catalog, SnapshotLimits limits) {
        return new IncidentAgentStateJson(
                StateMigrationChain.defaults(), new IncidentAgentStatePolicy(limits, catalog));
    }

    private static IncidentAgentStatePolicy.ReferenceCatalog validReferences() {
        Map<UUID, ReferenceRecord> records = new HashMap<>();
        records.put(EVIDENCE_ID.value(), new ReferenceRecord(EVIDENCE_ID.value(), RUN_ID, ReferenceType.EVIDENCE));
        records.put(HYPOTHESIS_ID.value(), new ReferenceRecord(HYPOTHESIS_ID.value(), RUN_ID, ReferenceType.HYPOTHESIS));
        records.put(PLAN_ID.value(), new ReferenceRecord(PLAN_ID.value(), RUN_ID, ReferenceType.ARTIFACT));
        records.put(RESULT_ID.value(), new ReferenceRecord(RESULT_ID.value(), RUN_ID, ReferenceType.ARTIFACT));
        return id -> Optional.ofNullable(records.get(id));
    }

    private static ReferenceType expectedType(UUID id) {
        if (id.equals(EVIDENCE_ID.value())) {
            return ReferenceType.EVIDENCE;
        }
        if (id.equals(HYPOTHESIS_ID.value())) {
            return ReferenceType.HYPOTHESIS;
        }
        return ReferenceType.ARTIFACT;
    }

    private static IncidentAgentState sample(List<String> warnings) {
        return new IncidentAgentState(
                IncidentAgentState.CURRENT_SCHEMA_VERSION,
                INCIDENT_ID,
                RUN_ID,
                "a2a-context-1",
                uuid(10),
                0,
                IncidentRunState.COLLECTING_EVIDENCE,
                null,
                PLAN_ID,
                1,
                STEP_ID,
                List.of(new AgentStepSnapshot(
                        STEP_ID,
                        StepAttemptState.COMPLETED,
                        List.of(new StepAttemptSnapshot(
                                new Attempt(1), new A2aTaskId(uuid(8)), StepAttemptState.COMPLETED, 2, RESULT_ID)))),
                List.of(EVIDENCE_ID),
                List.of(HYPOTHESIS_ID),
                null,
                null,
                new TokenBudgetSnapshot(10_000, 1_000),
                new UsageStatistics(700, 300, 25_000),
                new ReactLoopSnapshot(1, 1, 2, 1, List.of("action-a"), List.of("evidence-a")),
                List.of("database metrics"),
                warnings,
                null,
                null,
                false,
                NOW.plusSeconds(3600),
                NOW,
                NOW);
    }

    private static IncidentAgentState withVersion(IncidentAgentState state, long version) {
        return new IncidentAgentState(
                state.schemaVersion(), state.incidentId(), state.runId(), state.a2aContextId(),
                state.supervisorAgentSessionId(), version, state.status(), state.outcome(), state.planArtifactId(),
                state.planVersion(), state.currentStepId(), state.steps(), state.evidenceIds(), state.hypothesisIds(),
                state.remediationPlanArtifactId(), state.approvalId(), state.tokenBudget(), state.usage(),
                state.reactLoop(), state.missingEvidence(), state.warnings(), state.failureId(),
                state.finalReportArtifactId(), state.cancellationRequested(), state.deadline(),
                state.createdAt(), state.updatedAt());
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private static final class IncidentMemory implements IncidentAgentStateRepository {
        private final Map<RunId, IncidentAgentState> values = new HashMap<>();

        @Override
        public synchronized Optional<IncidentAgentState> load(RunId runId) {
            return Optional.ofNullable(values.get(runId));
        }

        @Override
        public synchronized void save(IncidentAgentState state, long expectedVersion) {
            long actual = Optional.ofNullable(values.get(state.runId())).map(IncidentAgentState::version).orElse(-1L);
            if (actual != expectedVersion) {
                throw new OptimisticLockFailure();
            }
            values.put(state.runId(), state);
        }
    }

    private static final class RuntimeMemory implements AgentRuntimeStatePort {
        private final Map<UUID, RuntimeState> values = new HashMap<>();

        @Override
        public Optional<RuntimeState> load(UUID sessionId) {
            return Optional.ofNullable(values.get(sessionId));
        }

        @Override
        public void store(RuntimeState state, long expectedVersion) {
            long actual = Optional.ofNullable(values.get(state.sessionId())).map(RuntimeState::version).orElse(-1L);
            if (actual != expectedVersion) {
                throw new OptimisticLockFailure();
            }
            values.put(state.sessionId(), state);
        }
    }

    private static final class TaskMemory implements A2aTaskStatePort {
        private final Map<RemoteTaskId, TaskState> values = new HashMap<>();

        @Override
        public Optional<TaskState> load(RemoteTaskId taskId) {
            return Optional.ofNullable(values.get(taskId));
        }

        @Override
        public void store(TaskState state, long expectedVersion) {
            long actual = Optional.ofNullable(values.get(state.taskId())).map(TaskState::version).orElse(-1L);
            if (actual != expectedVersion) {
                throw new OptimisticLockFailure();
            }
            values.put(state.taskId(), state);
        }
    }

    private static final class OptimisticLockFailure extends RuntimeException {
    }
}
