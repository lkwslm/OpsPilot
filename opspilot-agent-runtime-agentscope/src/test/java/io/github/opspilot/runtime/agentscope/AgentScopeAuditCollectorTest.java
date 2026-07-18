package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import io.github.opspilot.core.port.agent.ToolPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentScopeAuditCollectorTest {

    @Test
    void capturesOrderedBoundedAuditAndRedactsSensitiveFields() {
        List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        int[] call = {0};
        ChatPort chatPort = request -> {
            call[0]++;
            if (call[0] == 1) {
                return new ChatPort.ChatResponse(
                        "decision", "", List.of(new ChatPort.ToolCall(
                        "call-1", "MetricQueryTool", Map.of(
                        "query", "pool_active",
                        "apiKey", "top-secret",
                        "hiddenReasoning", "must-not-be-stored"))),
                        new ChatPort.TokenUsage(11, 3, 1), "tool_calls");
            }
            return new ChatPort.ChatResponse(
                    "result",
                    """
                    {"schemaVersion":"1.0.0","outcome":"CONCLUSIVE","summary":"Pool exhausted.",
                     "evidenceIds":["10000000-0000-4000-8000-000000000001"]}
                    """,
                    List.of(), new ChatPort.TokenUsage(20, 9, 0), "stop");
        };
        ToolPort toolPort = new ToolPort() {
            public String name() { return "MetricQueryTool"; }
            public String description() { return "query metrics"; }
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", true);
            }
            public ToolResult execute(Map<String, Object> input) {
                return new ToolResult(true, "active=20,max=20");
            }
        };
        StructuredDecisionRunner runner = new StructuredDecisionRunner(
                "chat-fixed", chatPort, List.of(toolPort), ignored -> { }, events::add);

        StructuredDecisionRunner.RunResult result = runner.run("diagnose", () -> false);

        assertTrue(result.success());
        assertEquals(List.of(
                        "CANCEL_SIGNAL",
                        "ROUND_STARTED",
                        "MODEL_COMPLETED",
                        "ACTION_SELECTED",
                        "TOOL_STARTED",
                        "TOOL_COMPLETED",
                        "CHECKPOINT",
                        "ROUND_STARTED",
                        "MODEL_COMPLETED",
                        "CHECKPOINT"),
                events.stream().map(RuntimeAuditSink.AuditEvent::type).toList());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
        RuntimeAuditSink.AuditEvent action = events.get(3);
        assertEquals(64, action.actionFingerprint().length());
        assertEquals("[REDACTED]", ((Map<?, ?>) action.attributes().get("arguments")).get("apiKey"));
        assertFalse(((Map<?, ?>) action.attributes().get("arguments")).containsKey("hiddenReasoning"));
        assertEquals(11, events.get(2).inputTokens());
        assertEquals(3, events.get(2).outputTokens());
        assertNotNull(events.get(6).checkpointId());
        String persistedAudit = events.toString();
        assertFalse(persistedAudit.contains("top-secret"));
        assertFalse(persistedAudit.contains("must-not-be-stored"));
    }

    @Test
    void capturesCancellationBeforeAnyModelOrToolCall() {
        int[] modelCalls = {0};
        List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        ChatPort chatPort = request -> {
            modelCalls[0]++;
            throw new AssertionError("model must not be called");
        };
        StructuredDecisionRunner runner = new StructuredDecisionRunner(
                "chat-fixed", chatPort, List.of(), ignored -> { }, events::add);

        StructuredDecisionRunner.RunResult result = runner.run("diagnose", () -> true);

        assertFalse(result.success());
        assertEquals("EXTERNAL_CANCELLED", result.reasonCode());
        assertEquals(0, modelCalls[0]);
        assertEquals(1, events.size());
        assertEquals(Boolean.TRUE, events.getFirst().cancelled());
    }
}
