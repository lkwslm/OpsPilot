package io.github.opspilot.core.transaction;

import io.github.opspilot.core.application.checkpoint.CheckpointService;
import io.github.opspilot.core.application.checkpoint.CheckpointService.CommitOutcome;
import io.github.opspilot.core.application.checkpoint.CommittedEventProjector;
import io.github.opspilot.core.application.checkpoint.CommittedEventProjector.ProjectionOutcome;
import io.github.opspilot.core.application.state.IncidentAgentStatePolicy;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.ReactLoopSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.UsageStatistics;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.port.repository.CheckpointContracts.BindingType;
import io.github.opspilot.core.port.repository.CheckpointContracts.CallAudit;
import io.github.opspilot.core.port.repository.CheckpointContracts.CallKind;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointConflict;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointStateReader;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointUnitOfWork;
import io.github.opspilot.core.port.repository.CheckpointContracts.DomainCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.DomainEvent;
import io.github.opspilot.core.port.repository.CheckpointContracts.ReferenceBinding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionContractTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final RunId RUN_ID = new RunId(uuid(2));

    @Test
    void everyCheckpointFailurePointLeavesNothingPartiallyVisible() {
        for (FailurePoint point : FailurePoint.values()) {
            InMemoryUnitOfWork unit = new InMemoryUnitOfWork();
            unit.failurePoint = point;
            try {
                unit.commit(command(state(0, IncidentRunState.PLANNING, false), -1));
            } catch (InjectedFailure expected) {
                // Test-only deterministic failure injection required by the transaction specification.
            }
            assertTrue(unit.states.isEmpty(), point.name());
            assertTrue(unit.audits.isEmpty(), point.name());
            assertTrue(unit.bindings.isEmpty(), point.name());
            assertTrue(unit.outbox.isEmpty(), point.name());
        }
    }

    @Test
    void successfulCheckpointAtomicallyExposesStateAuditBindingsAndOutbox() {
        InMemoryUnitOfWork unit = new InMemoryUnitOfWork();
        CheckpointCommand command = command(state(0, IncidentRunState.PLANNING, false), -1);
        unit.commit(command);

        assertEquals(command.state(), unit.load(RUN_ID).orElseThrow());
        assertEquals(command.callAudits(), unit.audits);
        assertEquals(command.bindings(), unit.bindings);
        assertEquals(command.outboxEvents(), unit.outbox);
    }

    @Test
    void casConflictRereadsAndRejudgesInsteadOfOverwritingTerminalState() {
        InMemoryUnitOfWork unit = new InMemoryUnitOfWork();
        unit.commit(command(state(0, IncidentRunState.PLANNING, false), -1));
        CheckpointService service = new CheckpointService(unit, unit);

        CheckpointCommand cancelled = command(state(1, IncidentRunState.CANCELLED, true), 0);
        assertEquals(CommitOutcome.COMMITTED, service.commit(cancelled, (stale, latest) -> Optional.empty()).outcome());
        CheckpointCommand staleCompletion = command(state(1, IncidentRunState.COMPLETED, false), 0);
        var rejected = service.commit(staleCompletion, (stale, latest) -> Optional.empty());
        assertEquals(CommitOutcome.CONFLICT_REJECTED, rejected.outcome());
        assertEquals(IncidentRunState.CANCELLED, unit.load(RUN_ID).orElseThrow().status());
        assertTrue(unit.load(RUN_ID).orElseThrow().cancellationRequested());

        InMemoryUnitOfWork retryUnit = new InMemoryUnitOfWork();
        retryUnit.commit(command(state(0, IncidentRunState.PLANNING, false), -1));
        retryUnit.commit(command(state(1, IncidentRunState.COLLECTING_EVIDENCE, false), 0));
        CheckpointService retryService = new CheckpointService(retryUnit, retryUnit);
        var retried = retryService.commit(command(state(1, IncidentRunState.RETRIEVING_KNOWLEDGE, false), 0),
                (stale, latest) -> Optional.of(command(
                        state(latest.version() + 1, IncidentRunState.RETRIEVING_KNOWLEDGE, false), latest.version())));
        assertEquals(CommitOutcome.COMMITTED_AFTER_REEVALUATION, retried.outcome());
        assertEquals(2, retryUnit.load(RUN_ID).orElseThrow().version());
    }

    @Test
    void domainFactsProjectOnlyAfterCommitAndDuplicateEventIsIdempotent() {
        assertNotEquals(DomainEvent.class, DomainCommand.class);
        ReceiptMemory receipts = new ReceiptMemory();
        AtomicInteger sideEffects = new AtomicInteger();
        CommittedEventProjector projector = new CommittedEventProjector(receipts,
                event -> sideEffects.incrementAndGet());
        DomainEvent event = command(state(0, IncidentRunState.PLANNING, false), -1).outboxEvents().getFirst();

        assertEquals(ProjectionOutcome.PROJECTED, projector.project(event));
        assertEquals(ProjectionOutcome.DUPLICATE, projector.project(event));
        assertEquals(1, sideEffects.get());
    }

    @Test
    void projectorFailureIsRetryableAndCannotRollBackCommittedCoreState() throws IOException {
        InMemoryUnitOfWork unit = new InMemoryUnitOfWork();
        CheckpointCommand committed = command(state(0, IncidentRunState.PLANNING, false), -1);
        unit.commit(committed);
        ReceiptMemory receipts = new ReceiptMemory();
        CommittedEventProjector projector = new CommittedEventProjector(receipts,
                event -> { throw new IllegalStateException("projection unavailable"); });

        assertEquals(ProjectionOutcome.RETRYABLE_FAILURE, projector.project(committed.outboxEvents().getFirst()));
        assertEquals(IncidentRunState.PLANNING, unit.load(RUN_ID).orElseThrow().status());
        assertEquals("PROJECTION_FAILED", receipts.errors.get(committed.outboxEvents().getFirst().eventId()));

        Path main = Path.of(System.getProperty("basedir"), "src", "main", "java");
        try (var files = Files.walk(main)) {
            assertFalse(files.filter(path -> path.toString().endsWith(".java"))
                    .anyMatch(path -> {
                        try {
                            return Files.readString(path).contains("InMemoryUnitOfWork");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }), "test UnitOfWork must not be present in production sources");
        }
    }

    private static CheckpointCommand command(IncidentAgentState state, long expectedVersion) {
        return new CheckpointCommand(
                UUID.randomUUID(), state, expectedVersion,
                List.of(new CallAudit(UUID.randomUUID(), RUN_ID, CallKind.A2A,
                        "dispatch-code-agent", "SUCCEEDED", NOW)),
                List.of(new ReferenceBinding(UUID.randomUUID(), RUN_ID, BindingType.ARTIFACT, uuid(20))),
                List.of(new DomainEvent(UUID.randomUUID(), RUN_ID, "STEP_CHECKPOINTED", state.version(), NOW)));
    }

    private static IncidentAgentState state(long version, IncidentRunState status, boolean cancelled) {
        return new IncidentAgentState(
                IncidentAgentState.CURRENT_SCHEMA_VERSION,
                new IncidentId(uuid(1)), RUN_ID, "a2a-context-1", uuid(3), version, status,
                null, null, 0, null, List.of(), List.of(), List.of(), null, null,
                new TokenBudgetSnapshot(10_000, 100), new UsageStatistics(50, 50, 1_000),
                new ReactLoopSnapshot(1, 1, 1, 1, List.of("action-a"), List.of("evidence-a")),
                List.of(), List.of(), null, null, cancelled, NOW.plusSeconds(60), NOW, NOW);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private enum FailurePoint { AFTER_STATE, AFTER_AUDIT, AFTER_BINDING, BEFORE_OUTBOX, BEFORE_COMMIT }

    /** Test-only UnitOfWork. It is never registered as a Provider or fallback. */
    private static final class InMemoryUnitOfWork implements CheckpointUnitOfWork, CheckpointStateReader {
        private final Map<RunId, IncidentAgentState> states = new HashMap<>();
        private final List<CallAudit> audits = new ArrayList<>();
        private final List<ReferenceBinding> bindings = new ArrayList<>();
        private final List<DomainEvent> outbox = new ArrayList<>();
        private FailurePoint failurePoint;

        @Override
        public synchronized void commit(CheckpointCommand command) {
            long actual = Optional.ofNullable(states.get(command.state().runId()))
                    .map(IncidentAgentState::version).orElse(-1L);
            if (actual != command.expectedVersion()) {
                throw new CheckpointConflict("expected " + command.expectedVersion() + " but found " + actual);
            }
            Map<RunId, IncidentAgentState> stagedStates = new HashMap<>(states);
            List<CallAudit> stagedAudits = new ArrayList<>(audits);
            List<ReferenceBinding> stagedBindings = new ArrayList<>(bindings);
            List<DomainEvent> stagedOutbox = new ArrayList<>(outbox);
            stagedStates.put(command.state().runId(), command.state());
            fail(FailurePoint.AFTER_STATE);
            stagedAudits.addAll(command.callAudits());
            fail(FailurePoint.AFTER_AUDIT);
            stagedBindings.addAll(command.bindings());
            fail(FailurePoint.AFTER_BINDING);
            fail(FailurePoint.BEFORE_OUTBOX);
            stagedOutbox.addAll(command.outboxEvents());
            fail(FailurePoint.BEFORE_COMMIT);
            states.clear(); states.putAll(stagedStates);
            audits.clear(); audits.addAll(stagedAudits);
            bindings.clear(); bindings.addAll(stagedBindings);
            outbox.clear(); outbox.addAll(stagedOutbox);
        }

        @Override
        public synchronized Optional<IncidentAgentState> load(RunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private void fail(FailurePoint point) {
            if (failurePoint == point) {
                throw new InjectedFailure();
            }
        }
    }

    private static final class ReceiptMemory implements CommittedEventProjector.ProjectionReceiptPort {
        private final Set<UUID> succeeded = new HashSet<>();
        private final Set<UUID> running = new HashSet<>();
        private final Map<UUID, String> errors = new HashMap<>();

        @Override
        public boolean tryStart(UUID eventId) {
            if (succeeded.contains(eventId) || running.contains(eventId)) {
                return false;
            }
            running.add(eventId);
            return true;
        }

        @Override
        public void succeeded(UUID eventId) {
            running.remove(eventId);
            succeeded.add(eventId);
        }

        @Override
        public void failed(UUID eventId, String errorCode, boolean retryable) {
            running.remove(eventId);
            errors.put(eventId, errorCode);
        }
    }

    private static final class InjectedFailure extends RuntimeException {
    }
}
