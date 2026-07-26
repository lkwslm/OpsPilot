package io.github.opspilot.core.governance;

import io.github.opspilot.core.application.governance.TokenBudgetManager;
import io.github.opspilot.core.application.governance.TokenBudgetManager.AttemptOutcome;
import io.github.opspilot.core.application.governance.TokenBudgetManager.BudgetExceededException;
import io.github.opspilot.core.application.governance.TokenBudgetManager.BudgetLimits;
import io.github.opspilot.core.application.governance.TokenBudgetManager.CostReservation;
import io.github.opspilot.core.application.governance.TokenBudgetManager.ReservationRequest;
import io.github.opspilot.core.application.governance.TokenBudgetManager.UsageScope;
import io.github.opspilot.core.application.governance.TokenBudgetManager.UsageSource;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetManagerTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final UsageScope SCOPE = new UsageScope("incident-1", "task-1", "agent-1");

    @Test
    void reservesBeforeCallAndReconcilesWithProviderUsage() {
        TokenBudgetManager manager = manager();
        UUID invocationId = UUID.randomUUID();
        manager.reserve(request(invocationId, 100, 50, 40L));

        var entry = manager.reconcile(invocationId, new ProviderUsage(80, 20, 30L),
                7, AttemptOutcome.SUCCEEDED, NOW.plusSeconds(1));

        assertEquals(UsageSource.PROVIDER, entry.source());
        assertEquals(80, entry.inputTokens());
        assertEquals(20, entry.outputTokens());
        assertEquals(7, entry.cachedTokens());
        assertEquals(30L, entry.costMicros());
        assertEquals("pricing-v1", entry.priceTableVersion());
        assertEquals(100, manager.snapshot().consumedTokens());
        assertEquals(0, manager.snapshot().reservedTokens());
    }

    @Test
    void failedAttemptWithoutUsageUsesVersionedConservativeEstimateAndNeverZero() {
        TokenBudgetManager manager = manager();
        UUID invocationId = UUID.randomUUID();
        manager.reserve(request(invocationId, 60, 40, null));

        var entry = manager.reconcile(invocationId, null, 0, AttemptOutcome.FAILED, NOW.plusSeconds(1));

        assertEquals(UsageSource.CONSERVATIVE_ESTIMATE, entry.source());
        assertEquals("chars-div-3-v1", entry.estimatorVersion());
        assertTrue(entry.totalTokens() > 0);
        assertEquals(AttemptOutcome.FAILED, entry.outcome());
        assertEquals(1, manager.snapshot().calls());
    }

    @Test
    void rejectsRequestsBeforeSendingWhenAnyFrozenLimitWouldBeExceeded() {
        TokenBudgetManager manager = manager();

        BudgetExceededException perCall = assertThrows(BudgetExceededException.class,
                () -> manager.reserve(request(UUID.randomUUID(), 501, 10, null)));
        assertEquals("INPUT_TOKENS_PER_CALL_EXCEEDED", perCall.getMessage());

        manager.reserve(request(UUID.randomUUID(), 400, 100, null));
        BudgetExceededException aggregate = assertThrows(BudgetExceededException.class,
                () -> manager.reserve(request(UUID.randomUUID(), 400, 100, null)));
        assertEquals("AGENT_TOKEN_BUDGET_EXCEEDED", aggregate.getMessage());
    }

    @Test
    void invocationCannotBeReservedOrReconciledTwice() {
        TokenBudgetManager manager = manager();
        UUID invocationId = UUID.randomUUID();
        ReservationRequest request = request(invocationId, 10, 10, null);
        manager.reserve(request);
        assertEquals("INVOCATION_ALREADY_ACCOUNTED",
                assertThrows(IllegalStateException.class, () -> manager.reserve(request)).getMessage());
        manager.reconcile(invocationId, null, 0, AttemptOutcome.CANCELLED, NOW.plusSeconds(1));
        assertEquals("INVOCATION_ALREADY_RECONCILED",
                assertThrows(IllegalStateException.class,
                        () -> manager.reconcile(invocationId, null, 0, AttemptOutcome.CANCELLED,
                                NOW.plusSeconds(2))).getMessage());
    }

    @Test
    void failedAttemptAndRetryUseSeparateInvocationIdsAndAreBothCharged() {
        TokenBudgetManager manager = manager();
        UUID first = UUID.randomUUID();
        UUID retry = UUID.randomUUID();
        manager.reserve(request(first, 30, 20, null));
        manager.reconcile(first, null, 0, AttemptOutcome.FAILED, NOW.plusSeconds(1));
        manager.reserve(request(retry, 30, 20, null));
        manager.reconcile(retry, new ProviderUsage(25, 10, null), 2,
                AttemptOutcome.SUCCEEDED, NOW.plusSeconds(2));

        assertEquals(2, manager.snapshot().calls());
        assertEquals(85, manager.snapshot().consumedTokens());
        assertEquals(null, manager.snapshot().ledgerEntries().get(1).costMicros());
    }

    @Test
    void concurrentReservationsCannotOversubscribeTheSameAgentBudget() throws Exception {
        TokenBudgetManager manager = manager();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfter(start, manager, request(UUID.randomUUID(), 400, 100, null)));
            var second = executor.submit(() -> reserveAfter(start, manager, request(UUID.randomUUID(), 400, 100, null)));
            start.countDown();

            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(500, manager.snapshot().reservedTokens());
    }

    private static TokenBudgetManager manager() {
        return new TokenBudgetManager(
                new BudgetLimits(500, 200, 800, 900, 1000, 3, 1000L),
                (reservation, outcome) -> new TokenBudgetManager.ConservativeUsage(
                        reservation.estimatedInputTokens(), reservation.reservedOutputTokens(), "chars-div-3-v1"));
    }

    private static ReservationRequest request(UUID id, long input, long output, Long cost) {
        return new ReservationRequest(id, SCOPE, input, output,
                new CostReservation(cost, cost == null ? null : "pricing-v1"), NOW);
    }

    private static boolean reserveAfter(
            CountDownLatch start, TokenBudgetManager manager, ReservationRequest request) throws InterruptedException {
        start.await();
        try {
            manager.reserve(request);
            return true;
        } catch (BudgetExceededException expected) {
            return false;
        }
    }
}
