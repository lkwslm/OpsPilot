package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class AgentScopeCheckpointWriterTest {

    @TempDir
    Path fallbackTrap;

    @Test
    void failsClosedWithoutFurtherCallsOrFallbackWhenCheckpointSaveFails() throws IOException {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        FailingStateStore store = new FailingStateStore();
        AgentScopeExecutionGuard guard = new AgentScopeExecutionGuard(
                new AgentScopeExecutionGuard.Limits(
                        3, Instant.now().plusSeconds(60), 2),
                () -> false,
                events::add);
        AgentScopeCheckpointWriter writer = new AgentScopeCheckpointWriter(store, guard);

        invokeModel(guard, modelCalls, 1);
        invokeTool(guard, toolCalls, "action-1");
        AgentScopeExecutionGuard.StopDecision failure = writer.save(
                "user-1", "session-1", "checkpoint", new Checkpoint("checkpoint-1"));
        invokeModel(guard, modelCalls, 2);
        invokeTool(guard, toolCalls, "action-after-failure");

        assertFalse(failure.permitted());
        assertEquals(AgentScopeExecutionGuard.STATE_PERSISTENCE_FAILED, failure.reasonCode());
        assertEquals(1, store.saveCalls.get());
        assertEquals(0, store.readCalls.get());
        assertEquals(1, modelCalls.get());
        assertEquals(1, toolCalls.get());
        assertEquals(1, events.size());
        assertEquals(AgentScopeExecutionGuard.STATE_PERSISTENCE_FAILED,
                events.getFirst().attributes().get("reasonCode"));
        try (var files = Files.list(fallbackTrap)) {
            assertEquals(0, files.count());
        }
    }

    private static void invokeModel(
            AgentScopeExecutionGuard guard, AtomicInteger calls, int round) {
        if (guard.beforeModelCall(round).permitted()) {
            calls.incrementAndGet();
        }
    }

    private static void invokeTool(
            AgentScopeExecutionGuard guard, AtomicInteger calls, String fingerprint) {
        if (guard.beforeToolCall(fingerprint).permitted()) {
            calls.incrementAndGet();
        }
    }

    private record Checkpoint(String id) implements State {
    }

    private static final class FailingStateStore implements AgentStateStore {
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger readCalls = new AtomicInteger();

        @Override
        public void save(String userId, String sessionId, String key, State state) {
            saveCalls.incrementAndGet();
            throw new IllegalStateException("injected PostgreSQL failure");
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> states) {
            saveCalls.incrementAndGet();
            throw new IllegalStateException("injected PostgreSQL failure");
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            readCalls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> type) {
            readCalls.incrementAndGet();
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            readCalls.incrementAndGet();
            return false;
        }

        @Override
        public void delete(String userId, String sessionId) {
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            readCalls.incrementAndGet();
            return Set.of();
        }
    }
}
