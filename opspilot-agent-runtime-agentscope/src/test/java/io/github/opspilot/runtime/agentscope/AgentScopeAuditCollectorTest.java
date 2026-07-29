package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentScopeAuditCollectorTest {

    @Test
    void capturesOrderedBoundedAuditAndRedactsSensitiveFields() {
        List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        AgentScopeAuditCollector collector = new AgentScopeAuditCollector(events::add);
        Map<String, Object> arguments = Map.of(
                "query", "pool_active",
                "apiKey", "top-secret",
                "hiddenReasoning", "must-not-be-stored");
        String fingerprint = collector.actionFingerprint("CALL_TOOL", "MetricQueryTool", arguments);

        collector.event("MODEL_COMPLETED", 1, null, 11, 3, null, false, Map.of());
        collector.event("ACTION_SELECTED", 1, fingerprint, null, null, null, false,
                Map.of("toolName", "MetricQueryTool", "arguments", arguments));
        collector.event("CHECKPOINT", 1, fingerprint, null, null,
                collector.checkpoint("call-1:SUCCEEDED"), false, Map.of());

        assertEquals(List.of("MODEL_COMPLETED", "ACTION_SELECTED", "CHECKPOINT"),
                events.stream().map(RuntimeAuditSink.AuditEvent::type).toList());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
        RuntimeAuditSink.AuditEvent action = events.get(1);
        assertEquals(64, action.actionFingerprint().length());
        assertEquals("[REDACTED]", ((Map<?, ?>) action.attributes().get("arguments")).get("apiKey"));
        assertFalse(((Map<?, ?>) action.attributes().get("arguments")).containsKey("hiddenReasoning"));
        assertEquals(11, events.getFirst().inputTokens());
        assertEquals(3, events.getFirst().outputTokens());
        assertNotNull(events.get(2).checkpointId());
        String persistedAudit = events.toString();
        assertFalse(persistedAudit.contains("top-secret"));
        assertFalse(persistedAudit.contains("must-not-be-stored"));
    }

    @Test
    void capturesCancellationBeforeAnyModelOrToolCall() {
        AtomicInteger modelCalls = new AtomicInteger();
        List<RuntimeAuditSink.AuditEvent> events = new ArrayList<>();
        AgentScopeExecutionGuard guard = new AgentScopeExecutionGuard(
                new AgentScopeExecutionGuard.Limits(2, Instant.now().plusSeconds(5), 2),
                () -> true,
                events::add);

        AgentScopeExecutionGuard.StopDecision result = guard.beforeModelCall(1);
        if (result.permitted()) {
            modelCalls.incrementAndGet();
        }

        assertFalse(result.permitted());
        assertEquals("EXTERNAL_CANCELLED", result.reasonCode());
        assertEquals(0, modelCalls.get());
        assertEquals(1, events.size());
        assertEquals(Boolean.TRUE, events.getFirst().cancelled());
    }
}
