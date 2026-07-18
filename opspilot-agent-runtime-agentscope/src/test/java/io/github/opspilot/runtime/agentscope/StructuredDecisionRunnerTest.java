package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.DecisionSummaryStore;
import io.github.opspilot.core.port.agent.ToolPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StructuredDecisionRunnerTest {

    private static final String VALID_RESULT = """
            {"schemaVersion":"1.0.0","outcome":"CONCLUSIVE","summary":"Pool exhausted.",
             "evidenceIds":["10000000-0000-4000-8000-000000000001"]}
            """;

    @Test
    void acceptsValidFirstResultAndPersistsOnlySummary() {
        Harness harness = new Harness(VALID_RESULT);

        StructuredDecisionRunner.RunResult result = harness.runner.run("diagnose");

        assertTrue(result.success());
        assertEquals(0, result.repairAttempts());
        assertEquals(1, harness.toolCalls.get());
        assertEquals(2, harness.chatCalls.get());
        assertEquals("CALL_TOOL", harness.saved.get().action());
        assertEquals("Pool exhausted.", harness.saved.get().summary());
        assertFalse(harness.saved.get().toString().contains(VALID_RESULT));
    }

    @Test
    void repairsInvalidResultExactlyOnce() {
        Harness harness = new Harness("{}", VALID_RESULT);

        StructuredDecisionRunner.RunResult result = harness.runner.run("diagnose");

        assertTrue(result.success());
        assertEquals(1, result.repairAttempts());
        assertEquals(1, harness.toolCalls.get());
        assertEquals(3, harness.chatCalls.get());
        assertEquals("CONCLUSIVE", harness.saved.get().outcome());
    }

    @Test
    void failsWhenResultRemainsInvalidAfterSingleRepair() {
        Harness harness = new Harness("{}", "{\"schemaVersion\":\"2.0.0\"}");

        StructuredDecisionRunner.RunResult result = harness.runner.run("diagnose");

        assertFalse(result.success());
        assertEquals(StructuredDecisionRunner.RESULT_INVALID, result.reasonCode());
        assertEquals(1, result.repairAttempts());
        assertEquals(1, harness.toolCalls.get());
        assertEquals(3, harness.chatCalls.get());
        assertNull(harness.saved.get());
    }

    private static final class Harness {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicReference<DecisionSummaryStore.DecisionSummary> saved = new AtomicReference<>();
        private final StructuredDecisionRunner runner;

        private Harness(String... resultResponses) {
            Deque<String> results = new ArrayDeque<>(List.of(resultResponses));
            ChatPort chatPort = request -> {
                int call = chatCalls.incrementAndGet();
                if (call == 1) {
                    return new ChatPort.ChatResponse(
                            "decision-1", "", List.of(new ChatPort.ToolCall(
                            "call-1", "MetricQueryTool", Map.of("query", "pool_active"))),
                            new ChatPort.TokenUsage(10, 2, 0), "tool_calls");
                }
                return new ChatPort.ChatResponse(
                        "result-" + call, results.removeFirst(), List.of(),
                        new ChatPort.TokenUsage(10, 10, 0), "stop");
            };
            ToolPort toolPort = new ToolPort() {
                public String name() { return "MetricQueryTool"; }
                public String description() { return "query metrics"; }
                public Map<String, Object> inputSchema() {
                    return Map.of(
                            "type", "object",
                            "required", new ArrayList<>(List.of("query")),
                            "properties", Map.of("query", Map.of("type", "string")));
                }
                public ToolResult execute(Map<String, Object> input) {
                    toolCalls.incrementAndGet();
                    return new ToolResult(true, "active=20,max=20");
                }
            };
            runner = new StructuredDecisionRunner(
                    "chat-fixed", chatPort, List.of(toolPort), saved::set);
        }
    }
}
