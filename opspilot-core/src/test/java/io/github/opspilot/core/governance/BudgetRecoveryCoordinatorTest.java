package io.github.opspilot.core.governance;

import io.github.opspilot.core.application.checkpoint.CheckpointService;
import io.github.opspilot.core.application.governance.BudgetRecoveryCoordinator;
import io.github.opspilot.core.application.governance.BudgetRecoveryCoordinator.RecoveryAction;
import io.github.opspilot.core.application.governance.BudgetRecoveryCoordinator.RecoveryRequest;
import io.github.opspilot.core.application.governance.BudgetRecoveryCoordinator.TokenBudgetExceededException;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.ReactLoopSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.UsageStatistics;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointConflict;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointStateReader;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointUnitOfWork;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetRecoveryCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final UUID CHECKPOINT_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void appliesTheFrozenRecoveryOrderAndStopsAsSoonAsTheRequestFits() {
        Store store = new Store(state(900));
        BudgetRecoveryCoordinator coordinator = coordinator(store);

        var result = coordinator.recover(store.state, request(250, 50, 3, 1, 60,
                80, 80, 80));

        assertTrue(result.newModelCallsAllowed());
        assertEquals(List.of(RecoveryAction.COMPACT_CONTEXT,
                RecoveryAction.REDUCE_KNOWLEDGE_CANDIDATES), result.actions());
        assertEquals(80, result.requiredTokensAfterRecovery());
        assertEquals(1, result.retainedKnowledgeCandidates());
        assertEquals(0, store.commits);
    }

    @Test
    void exhaustionAtomicallySavesMissingWorkRemainingBudgetAndGeneratingReportState() {
        Store store = new Store(state(950));
        BudgetRecoveryCoordinator coordinator = coordinator(store);

        var result = coordinator.recover(store.state, request(500, 50, 2, 1, 25,
                50, 50, 50));

        assertFalse(result.newModelCallsAllowed());
        assertEquals(BudgetRecoveryCoordinator.TOKEN_BUDGET_EXCEEDED, result.errorCode());
        assertEquals(List.of(RecoveryAction.COMPACT_CONTEXT, RecoveryAction.REDUCE_KNOWLEDGE_CANDIDATES,
                RecoveryAction.SHRINK_CODE_AND_TIME_WINDOW, RecoveryAction.SPLIT_SUBTASK,
                RecoveryAction.SUPERVISOR_REPLAN), result.actions());
        assertEquals(1, store.commits);
        IncidentAgentState reloaded = store.load(store.state.runId()).orElseThrow();
        assertEquals(IncidentRunState.GENERATING_REPORT, reloaded.status());
        assertEquals(50, reloaded.tokenBudget().limit() - reloaded.tokenBudget().consumed());
        assertTrue(reloaded.missingEvidence().contains("verify database saturation"));
        assertTrue(reloaded.warnings().contains("limited evidence window"));
        assertTrue(reloaded.warnings().contains("NEXT_STEP:produce a bounded partial report"));
        assertEquals("TOKEN_BUDGET_EXHAUSTED", store.lastCommand.outboxEvents().getFirst().factType());
    }

    @Test
    void aReloadedExhaustionCheckpointKeepsNewModelCallsStopped() {
        Store store = new Store(state(950));
        coordinator(store).recover(store.state, request(500, 0, 1, 1, 0, 0, 0, 0));
        IncidentAgentState reloaded = store.load(store.state.runId()).orElseThrow();

        BudgetRecoveryCoordinator restarted = coordinator(store);

        assertThrows(TokenBudgetExceededException.class,
                () -> restarted.requireNewModelCallAllowed(reloaded));
    }

    private static BudgetRecoveryCoordinator coordinator(Store store) {
        return new BudgetRecoveryCoordinator(new CheckpointService(store, store),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> CHECKPOINT_ID);
    }

    private static RecoveryRequest request(
            long required,
            long compactable,
            int candidates,
            int minimumCandidates,
            long perCandidate,
            long shrinkable,
            long splittable,
            long replannable) {
        return new RecoveryRequest(required, compactable, candidates, minimumCandidates, perCandidate,
                shrinkable, splittable, replannable,
                List.of("verify database saturation"), List.of("limited evidence window"),
                "produce a bounded partial report");
    }

    private static IncidentAgentState state(long consumed) {
        return new IncidentAgentState(IncidentAgentState.CURRENT_SCHEMA_VERSION,
                new IncidentId(UUID.randomUUID()), new RunId(UUID.randomUUID()), "a2a-context",
                UUID.randomUUID(), 3, IncidentRunState.RETRIEVING_KNOWLEDGE, null,
                null, 1, null, List.of(), List.of(), List.of(), null, null,
                new TokenBudgetSnapshot(1_000, consumed), new UsageStatistics(consumed, 0, 0),
                new ReactLoopSnapshot(1, 2, 1, 0, List.of(), List.of()),
                List.of(), List.of(), null, null, false, NOW.plusSeconds(60), NOW.minusSeconds(10), NOW);
    }

    private static final class Store implements CheckpointUnitOfWork, CheckpointStateReader {
        private IncidentAgentState state;
        private int commits;
        private CheckpointCommand lastCommand;

        private Store(IncidentAgentState state) {
            this.state = state;
        }

        @Override
        public synchronized void commit(CheckpointCommand command) {
            if (command.expectedVersion() != state.version()) {
                throw new CheckpointConflict("stale checkpoint");
            }
            lastCommand = command;
            state = command.state();
            commits++;
        }

        @Override
        public synchronized Optional<IncidentAgentState> load(RunId runId) {
            return state.runId().equals(runId) ? Optional.of(state) : Optional.empty();
        }
    }
}
