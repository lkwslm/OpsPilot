package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentScopeExecutionGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void stopsAtMaximumRoundsAndNeverCallsAgain() {
        Harness harness = new Harness(1, NOW.plusSeconds(60), false);

        harness.model(1);
        AgentScopeExecutionGuard.StopDecision stop = harness.model(2);
        harness.tool("after-stop", 1);
        harness.model(3);

        harness.assertStopped(stop, AgentScopeExecutionGuard.MAX_ROUNDS, 1, 0);
    }

    @Test
    void stopsAtDeadlineAndNeverCallsAgain() {
        Harness harness = new Harness(3, NOW, false);

        AgentScopeExecutionGuard.StopDecision stop = harness.model(1);
        harness.tool("after-stop", 1);
        harness.model(2);

        harness.assertStopped(stop, AgentScopeExecutionGuard.DEADLINE_EXCEEDED, 0, 0);
    }

    @Test
    void stopsOnExternalCancellationAndNeverCallsAgain() {
        Harness harness = new Harness(3, NOW.plusSeconds(60), true);

        AgentScopeExecutionGuard.StopDecision stop = harness.model(1);
        harness.tool("after-stop", 1);
        harness.model(2);

        harness.assertStopped(stop, AgentScopeExecutionGuard.EXTERNAL_CANCELLED, 0, 0);
    }

    @Test
    void stopsBeforeRepeatedActionAndNeverCallsAgain() {
        Harness harness = new Harness(3, NOW.plusSeconds(60), false);

        harness.model(1);
        harness.tool("same-action", 1);
        AgentScopeExecutionGuard.StopDecision stop = harness.tool("same-action", 1);
        harness.model(2);
        harness.tool("after-stop", 1);

        harness.assertStopped(stop, AgentScopeExecutionGuard.DUPLICATE_ACTION, 1, 1);
    }

    @Test
    void stopsAfterTwoRoundsWithoutNewEvidenceAndNeverCallsAgain() {
        Harness harness = new Harness(4, NOW.plusSeconds(60), false);

        harness.model(1);
        harness.tool("action-1", 0);
        harness.model(2);
        AgentScopeExecutionGuard.StopDecision stop = harness.tool("action-2", 0);
        harness.model(3);
        harness.tool("after-stop", 1);

        harness.assertStopped(stop, AgentScopeExecutionGuard.NO_PROGRESS, 2, 2);
    }

    private static final class Harness {
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        private final AgentScopeExecutionGuard guard;

        private Harness(int maxRounds, Instant deadline, boolean cancelled) {
            guard = new AgentScopeExecutionGuard(
                    new AgentScopeExecutionGuard.Limits(maxRounds, deadline, 2),
                    new AtomicBoolean(cancelled)::get,
                    events::add,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private AgentScopeExecutionGuard.StopDecision model(int round) {
            AgentScopeExecutionGuard.StopDecision decision = guard.beforeModelCall(round);
            if (decision.permitted()) {
                modelCalls.incrementAndGet();
            }
            return decision;
        }

        private AgentScopeExecutionGuard.StopDecision tool(String fingerprint, int newEvidenceCount) {
            AgentScopeExecutionGuard.StopDecision decision = guard.beforeToolCall(fingerprint);
            if (!decision.permitted()) {
                return decision;
            }
            toolCalls.incrementAndGet();
            return guard.afterToolCall(newEvidenceCount);
        }

        private void assertStopped(
                AgentScopeExecutionGuard.StopDecision stop,
                String reasonCode,
                int expectedModelCalls,
                int expectedToolCalls) {
            assertFalse(stop.permitted());
            assertEquals(reasonCode, stop.reasonCode());
            assertEquals(stop, guard.status());
            assertEquals(expectedModelCalls, modelCalls.get());
            assertEquals(expectedToolCalls, toolCalls.get());
            assertEquals(1, events.size());
            assertEquals("EXECUTION_STOPPED", events.getFirst().type());
            assertEquals(reasonCode, events.getFirst().attributes().get("reasonCode"));
            assertTrue(events.getFirst().sequence() > 0);
        }
    }
}
