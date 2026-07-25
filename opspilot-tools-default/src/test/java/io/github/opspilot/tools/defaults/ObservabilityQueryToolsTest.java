package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.*;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ObservabilityQueryToolsTest {
    @Test
    void sixCapabilityToolsReturnOnlyBoundedEvidenceAndArtifactReferences() {
        RuntimeEvidenceNormalizer normalizer = new RuntimeEvidenceNormalizer();
        List<ObservabilityQueryTool> tools = List.of(
                new LogQueryTool(port(false, false), normalizer, allow(), audit()),
                new MetricQueryTool(port(false, false), normalizer, allow(), audit()),
                new TraceQueryTool(port(false, false), normalizer, allow(), audit()),
                new HealthQueryTool(port(false, false), normalizer, allow(), audit()),
                new TopologyQueryTool(port(false, false), normalizer, allow(), audit()),
                new ConfigReadTool(port(false, false), normalizer, allow(), audit()));
        List<String> templates = List.of("log/errors-v1", "metric/http-v1", "trace/service-v1",
                "health/readiness-v1", "topology/compose-v1", "config/allowlist-v1");

        for (int index = 0; index < tools.size(); index++) {
            ToolResult result = tools.get(index).execute(request(templates.get(index), Map.of()));
            assertEquals(ToolStatus.SUCCEEDED, result.status(), tools.get(index).name());
            assertEquals(1, result.observationBatchIds().size());
            assertEquals(1, result.evidenceIds().size());
            assertEquals(1, result.artifactIds().size());
            assertTrue(result.summary().length() <= 512);
            assertEquals("1.0.0", tools.get(index).inputSchemaVersion());
        }
    }

    @Test
    void emptyDeniedInvalidAndTechnicalFailureAreDistinctAndPreflightDenialSkipsSource() {
        RuntimeEvidenceNormalizer normalizer = new RuntimeEvidenceNormalizer();
        assertEquals(ToolStatus.EMPTY, new MetricQueryTool(port(true, false), normalizer, allow(), audit())
                .execute(request("metric/http-v1", Map.of())).status());
        assertEquals(ToolStatus.FAILED, new MetricQueryTool(port(false, true), normalizer, allow(), audit())
                .execute(request("metric/http-v1", Map.of())).status());

        AtomicInteger calls = new AtomicInteger();
        ObservabilityQueryPort counting = command -> { calls.incrementAndGet(); return port(false, false).collect(command); };
        MetricQueryTool denied = new MetricQueryTool(counting, normalizer, (target, run, resource) -> false, audit());
        assertEquals(ToolStatus.DENIED, denied.execute(request("metric/http-v1", Map.of())).status());
        assertEquals(0, calls.get());

        MetricQueryTool injection = new MetricQueryTool(counting, normalizer, allow(), audit());
        assertEquals(ToolStatus.DENIED, injection.execute(request(
                "metric/http-v1", Map.of("promql", "rate(http_requests_total[5m])"))).status());
        assertEquals(0, calls.get());
    }

    private static ToolRequest request(String template, Map<String, Object> parameters) {
        return new ToolRequest("1.0.0", "sample-commerce", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), resource(), Instant.now().minusSeconds(30), Instant.now().plusSeconds(30),
                template, parameters, null, false, Set.of(resource().resourceId()));
    }

    private static ObservabilityQueryPort port(boolean empty, boolean fail) {
        return command -> {
            if (fail) throw new IllegalStateException("SOURCE_TIMEOUT");
            byte[] content = "{\"actual\":true}".getBytes();
            RawArtifact artifact = new RawArtifact(UUID.randomUUID(), "application/json",
                    ObservationContracts.sha256(content), content);
            SourceDescriptor source = new SourceDescriptor("source", SourceKind.PROMETHEUS, "adapter", "1.0.0",
                    "observability-source://sample/source", "local", Map.of(), Set.of(command.signal()));
            ObservationQuery query = new ObservationQuery(command.queryTemplateId(),
                    ObservationContracts.sha256("parameters"), command.windowStart(), command.windowEnd(), resource());
            List<ObservationRecord> records = empty ? List.of() : List.of(new ObservationRecord(
                    UUID.randomUUID(), command.signal(), resource(), Instant.now(), "bounded fact",
                    artifact.artifactId(), Map.of(), new ObservationQuality(1, true, false, 1.0, List.of())));
            return new ObservabilityQueryPort.QueryCollection(List.of(new ObservationBatch(
                    "1.0.0", UUID.randomUUID(), source, query, Instant.now(), null, records, artifact)));
        };
    }

    private static ResourceRef resource() {
        return new ResourceRef("service:order-service", ResourceType.SERVICE,
                "sample-commerce", "order-service", "local", Map.of());
    }

    private static ToolAuthorizationPort allow() { return (target, run, resource) -> true; }
    private static ToolAuditSink audit() { return (tool, run, outcome) -> { }; }
}
