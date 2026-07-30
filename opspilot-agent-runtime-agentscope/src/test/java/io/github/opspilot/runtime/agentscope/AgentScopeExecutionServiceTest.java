package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.github.opspilot.core.port.agent.AgentExecutionService;
import io.github.opspilot.core.port.agent.ChatPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentScopeExecutionServiceTest {

    @Test
    void executesOfficialReactLoopAndPersistsOnlyControlledJournal() {
        MemoryStateStore stateStore = new MemoryStateStore();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatPort chat = request -> {
            modelCalls.incrementAndGet();
            return new ChatPort.ChatResponse(
                    "response-1", "bounded decision", List.of(),
                    new ChatPort.TokenUsage(3, 2, 0), "stop");
        };
        List<io.github.opspilot.core.port.agent.RuntimeAuditSink.AuditEvent> audit =
                new java.util.ArrayList<>();

        try (AgentScopeExecutionService service = new AgentScopeExecutionService(
                chat, List.of(), stateStore, audit::add)) {
            AgentExecutionService.ExecutionResult result = service.execute(request(
                    "execution-1",
                    AgentExecutionService.ExecutionSession.specialist(
                            "diagnosis", "task-1", 1, false),
                    AgentExecutionService.CancellationToken.never(),
                    Instant.now().plusSeconds(30)));

            assertEquals(AgentExecutionService.ExecutionOutcome.COMPLETED, result.outcome());
            assertEquals("bounded decision", result.decision().summary());
            assertEquals(1, result.usage().modelCalls());
            assertEquals(5, result.usage().totalTokens());
            assertNotNull(result.checkpoint());
            assertEquals(1, modelCalls.get());
            AgentScopeExecutionService.ExecutionJournalState journal = stateStore
                    .get("opspilot-system", "diagnosis:task-1",
                            "opspilot.execution.checkpoint",
                            AgentScopeExecutionService.ExecutionJournalState.class)
                    .orElseThrow();
            assertEquals("bounded decision", journal.decisionSummary());
            String persisted = journal.toString().toLowerCase();
            assertFalse(persisted.contains("system prompt"));
            assertFalse(persisted.contains("secret-value"));
            assertFalse(persisted.contains("chain_of_thought"));
        }
    }

    @Test
    void continuationRecoversSameSessionAndNewAttemptUsesIsolatedSession() {
        MemoryStateStore stateStore = new MemoryStateStore();
        ChatPort chat = request -> new ChatPort.ChatResponse(
                "response", "done", List.of(), new ChatPort.TokenUsage(1, 1, 0), "stop");
        List<io.github.opspilot.core.port.agent.RuntimeAuditSink.AuditEvent> audit =
                new java.util.ArrayList<>();

        try (AgentScopeExecutionService service = new AgentScopeExecutionService(
                chat, List.of(), stateStore, audit::add)) {
            service.execute(request("first",
                    AgentExecutionService.ExecutionSession.specialist(
                            "diagnosis", "task-1", 1, false),
                    AgentExecutionService.CancellationToken.never(), Instant.now().plusSeconds(30)));
            AgentExecutionService.ExecutionResult continuation = service.execute(request("continued",
                    AgentExecutionService.ExecutionSession.specialist(
                            "diagnosis", "task-1", 1, true),
                    AgentExecutionService.CancellationToken.never(), Instant.now().plusSeconds(30)));
            AgentExecutionService.ExecutionResult retry = service.execute(request("retry",
                    AgentExecutionService.ExecutionSession.specialist(
                            "diagnosis", "task-2", 2, false),
                    AgentExecutionService.CancellationToken.never(), Instant.now().plusSeconds(30)));

            assertTrue(continuation.events().stream()
                    .anyMatch(event -> event.eventType().equals("CHECKPOINT_RECOVERED")));
            assertFalse(retry.events().stream()
                    .anyMatch(event -> event.eventType().equals("CHECKPOINT_RECOVERED")));
            assertTrue(stateStore.exists("opspilot-system", "diagnosis:task-1"));
            assertTrue(stateStore.exists("opspilot-system", "diagnosis:task-2"));
        }
    }

    @Test
    void parentDeadlineCancelsWaitAndDiscardsLateModelResult() {
        MemoryStateStore stateStore = new MemoryStateStore();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatPort slowChat = request -> {
            modelCalls.incrementAndGet();
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ChatPort.ChatResponse(
                    "late", "must not commit", List.of(),
                    new ChatPort.TokenUsage(1, 1, 0), "stop");
        };

        long started = System.nanoTime();
        AgentExecutionService.ExecutionResult result;
        try (AgentScopeExecutionService service = new AgentScopeExecutionService(
                slowChat, List.of(), stateStore, event -> { })) {
            result = service.execute(request("deadline",
                    AgentExecutionService.ExecutionSession.supervisor("run-1", 1, false),
                    AgentExecutionService.CancellationToken.never(),
                    Instant.now().plusSeconds(15)));
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(AgentExecutionService.ExecutionOutcome.TERMINATED, result.outcome());
        assertEquals(AgentScopeExecutionGuard.DEADLINE_EXCEEDED, result.terminationReason());
        assertTrue(elapsedMillis < 20_000, "elapsed=" + elapsedMillis);
        assertEquals(1, modelCalls.get());
        assertNotNull(result.checkpoint());
    }

    private static AgentExecutionService.ExecutionRequest request(
            String executionId,
            AgentExecutionService.ExecutionSession session,
            AgentExecutionService.CancellationToken cancellation,
            Instant deadline) {
        return new AgentExecutionService.ExecutionRequest(
                executionId,
                session.sessionId().split(":", 2)[0],
                "diagnosis",
                "Use evidence only. Secret-value must never be persisted as a prompt.",
                "chat-fixed",
                "diagnose without storing chain_of_thought",
                List.of(),
                new AgentExecutionService.ExecutionLimits(3, 3, 0, 100, 2, deadline),
                session,
                Map.of("runId", "run-1"),
                cancellation);
    }

    private static final class MemoryStateStore implements AgentStateStore {
        private final Map<String, State> values = new HashMap<>();

        public void save(String userId, String sessionId, String key, State state) {
            values.put(path(userId, sessionId, key), state);
        }

        public void save(String userId, String sessionId, String key, List<? extends State> states) {
            throw new UnsupportedOperationException();
        }

        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            State state = values.get(path(userId, sessionId, key));
            return state == null ? Optional.empty() : Optional.of(type.cast(state));
        }

        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> type) {
            return List.of();
        }

        public boolean exists(String userId, String sessionId) {
            String prefix = userId + "/" + sessionId + "/";
            return values.keySet().stream().anyMatch(key -> key.startsWith(prefix));
        }

        public void delete(String userId, String sessionId) {
            String prefix = userId + "/" + sessionId + "/";
            values.keySet().removeIf(key -> key.startsWith(prefix));
        }

        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }

        private static String path(String userId, String sessionId, String key) {
            return userId + "/" + sessionId + "/" + key;
        }
    }
}
