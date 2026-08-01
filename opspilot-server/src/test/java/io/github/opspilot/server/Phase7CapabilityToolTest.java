package io.github.opspilot.server;

import io.github.opspilot.core.port.agent.ToolPort.ToolStatus;
import io.github.opspilot.core.application.profile.AgentProfile.AgentRole;
import io.github.opspilot.runtime.agentscope.BundledAgentProfiles;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability.STRUCTURED_OUTPUT;
import static io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability.TOOL_CALLS;

class Phase7CapabilityToolTest {

    @Test
    void mapsProfileCapabilitiesIntoTheRuntimeProviderBoundary() {
        var profiles = new BundledAgentProfiles().loadAll();
        var evidence = profiles.stream()
                .filter(profile -> profile.role() == AgentRole.EVIDENCE_COLLECTOR)
                .findFirst().orElseThrow();
        var diagnosis = profiles.stream()
                .filter(profile -> profile.role() == AgentRole.DIAGNOSIS)
                .findFirst().orElseThrow();

        assertEquals(Set.of(TOOL_CALLS, STRUCTURED_OUTPUT),
                Phase7AgentExecutor.providerCapabilities(evidence));
        assertEquals(Set.of(STRUCTURED_OUTPUT),
                Phase7AgentExecutor.providerCapabilities(diagnosis));
    }

    @Test
    void executesTheGovernedCapabilityOnceAndExposesSignalSpecificViews() throws Exception {
        UUID evidenceId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var lazy = new Phase7CapabilityTool.LazyResult(() -> {
            calls.incrementAndGet();
            return """
                    {"evidence":[{"evidenceId":"%s","evidenceCode":"metric.order.pending_positive",
                    "signalType":"METRIC","artifactIds":["%s"]}]}
                    """.formatted(evidenceId, artifactId);
        });

        var metric = new Phase7CapabilityTool("MetricQueryTool", lazy).execute(Map.of());
        var log = new Phase7CapabilityTool("LogQueryTool", lazy).execute(Map.of());

        assertEquals(1, calls.get());
        assertEquals(ToolStatus.SUCCEEDED, metric.status());
        assertTrue(metric.summary().contains("metric.order.pending_positive"));
        assertEquals(1, metric.evidenceIds().size());
        assertEquals(1, metric.artifactIds().size());
        assertEquals(ToolStatus.SUCCEEDED, log.status());
        assertTrue(log.summary().contains("NO_MATCH"));
        assertEquals(1, log.evidenceIds().size());
        assertEquals(lazy.requireExecuted(), lazy.get());
    }

    @Test
    void capabilityFailureIsFailClosedAndCannotBePublishedAsExecuted() {
        var lazy = new Phase7CapabilityTool.LazyResult(() -> {
            throw new IllegalStateException("source unavailable");
        });

        var result = new Phase7CapabilityTool("KnowledgeSearchTool", lazy).execute(Map.of());

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals("PHASE7_CAPABILITY_FAILED", result.errorCode());
        assertThrows(IllegalStateException.class, lazy::requireExecuted);
    }

    @Test
    void preservesStableCapabilityFailureCodeForAuditWithoutLeakingDetails() {
        var lazy = new Phase7CapabilityTool.LazyResult(() -> {
            throw new IllegalStateException("RERANK_RESULT_COUNT_MISMATCH");
        });

        var result = new Phase7CapabilityTool("KnowledgeSearchTool", lazy).execute(Map.of());

        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals("RERANK_RESULT_COUNT_MISMATCH", result.errorCode());
    }
}
