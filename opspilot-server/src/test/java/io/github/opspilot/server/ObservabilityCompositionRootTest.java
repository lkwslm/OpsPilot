package io.github.opspilot.server;

import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ObservabilityCompositionRootTest {
    @TempDir Path temporary;

    @Test
    void compositionRootExplicitlyPublishesBothJaegerVersionsAndSixVendorNeutralTools() {
        var adapters = OpsPilotCompositionRoot.composeObservabilityAdapters(
                new OpsPilotCompositionRoot.ObservabilityAdapterConfig(
                        URI.create("http://127.0.0.1:9090"), URI.create("http://127.0.0.1:16686"),
                        URI.create("http://127.0.0.1:26686"),
                        temporary.resolve("sample.jsonl"), URI.create("http://127.0.0.1:8081/actuator/health"),
                        temporary.resolve("compose.yml"), URI.create("http://127.0.0.1:8082/health"),
                        temporary.resolve("application.properties"), Set.of("spring.datasource.hikari.maximum-pool-size")));
        assertEquals(8, adapters.snapshot().adapters().size());
        assertEquals(Set.of("jaeger-trace-v1", "jaeger-trace-v2"), adapters.snapshot().adapters().stream()
                .map(adapter -> adapter.adapterId())
                .filter(adapterId -> adapterId.startsWith("jaeger-trace"))
                .collect(java.util.stream.Collectors.toSet()));

        ObservabilityQueryPort port = command -> new ObservabilityQueryPort.QueryCollection(java.util.List.of());
        var tools = OpsPilotCompositionRoot.composeObservabilityTools(
                port, new RuntimeEvidenceNormalizer(), (target, run, resource) -> true,
                (name, run, status) -> { });
        assertEquals(Set.of("LogQueryTool", "MetricQueryTool", "TraceQueryTool", "HealthQueryTool",
                        "TopologyQueryTool", "ConfigReadTool"),
                tools.stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet()));
    }
}
