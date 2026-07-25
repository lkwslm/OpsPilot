package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real Phase 4 Source -> Observation -> Evidence/Artifact smoke gate. */
final class Phase4SmokeProcess {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private Phase4SmokeProcess() { }

    static void run(Map<String, String> environment) throws Exception {
        Path output = Path.of(environment.getOrDefault("PHASE4_EVIDENCE_DIR", "/evidence"));
        Files.createDirectories(output);
        JaegerMode jaegerMode = JaegerMode.parse(environment.getOrDefault("JAEGER_MODE", "dual"));
        ResourceRef resource = new ResourceRef("service:sample-gateway", ResourceType.SERVICE,
                "sample-commerce", "sample-gateway", "local", Map.of());
        Instant end = Instant.now();
        Instant start = end.minusSeconds(600);
        SourceExecutionContext execution = SourceExecutionContext.authorizedUntil(end.plusSeconds(20));
        var registry = OpsPilotCompositionRoot.composeObservabilityAdapters(
                new OpsPilotCompositionRoot.ObservabilityAdapterConfig(
                        URI.create(required(environment, "PROMETHEUS_URL")),
                        jaegerMode.v1() ? URI.create(required(environment, "JAEGER_V1_URL", "JAEGER_URL")) : null,
                        jaegerMode.v2() ? URI.create(required(environment, "JAEGER_V2_URL")) : null,
                        Path.of("/config/sample.jsonl"), URI.create("http://inventory-service:8080/actuator/health"),
                        Path.of("/config/docker-compose.yml"), URI.create("http://sample-gateway:8080/actuator/health"),
                        Path.of("/config/application.properties"), Set.of("spring.datasource.hikari.maximum-pool-size")));
        ObservationBatch metric = registry.require("prometheus-metric", "1.0.0").query(
                new ObservationQuery("metric/http-v1", ObservationContracts.sha256("{}"), start, end, resource),
                execution);
        ObservationQuery traceQuery = new ObservationQuery(
                "trace/service-v1", ObservationContracts.sha256("{}"), start, end, resource);
        List<ObservationBatch> batches = new ArrayList<>();
        batches.add(metric);
        if (jaegerMode.v1()) {
            batches.add(registry.require("jaeger-trace-v1", "1.0.0").query(traceQuery, execution));
        }
        if (jaegerMode.v2()) {
            batches.add(registry.require("jaeger-trace-v2", "1.0.0").query(traceQuery, execution));
        }
        if (batches.stream().anyMatch(batch -> batch.observations().isEmpty())) {
            throw new IllegalStateException("PHASE4_TELEMETRY_EMPTY");
        }

        var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(batches,
                new NormalizationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        if (jaegerMode == JaegerMode.DUAL) {
            Set<String> expectedSources = Set.of("phase0-jaeger-v1", "phase0-jaeger-v2");
            boolean dualProvenance = bundle.evidence().stream()
                    .filter(evidence -> evidence.signalType() == ObservationContracts.SignalType.TRACE)
                    .anyMatch(evidence -> evidence.provenanceRefs().stream()
                            .map(reference -> reference.sourceId())
                            .collect(java.util.stream.Collectors.toSet()).equals(expectedSources));
            if (!dualProvenance) {
                throw new IllegalStateException("PHASE4_JAEGER_DUAL_PROVENANCE_MISSING");
            }
        }

        Map<String, String> artifactHashes = new LinkedHashMap<>();
        for (ObservationBatch batch : batches) {
            Files.write(output.resolve(batch.rawArtifact().artifactId() + ".raw"), batch.rawArtifact().content());
            artifactHashes.put(batch.rawArtifact().artifactId().toString(), batch.rawArtifact().sha256());
        }
        Map<String, Object> report = Map.of(
                "schemaVersion", "1.0.0",
                "generatedAt", Instant.now(),
                "jaegerMode", jaegerMode.externalName,
                "capabilitySnapshot", registry.snapshot(),
                "observationBatches", batches.stream().map(ObservationBatch::batchId).toList(),
                "artifactSha256", artifactHashes,
                "evidenceBundle", bundle);
        Path reportPath = output.resolve("phase4-observability-report-" + jaegerMode.externalName + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        System.out.printf("PHASE4_SMOKE_OK mode=%s report=%s batches=%d evidence=%d%n",
                jaegerMode.externalName, reportPath, bundle.observationBatchIds().size(), bundle.evidence().size());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String required(Map<String, String> environment, String name, String legacyName) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? required(environment, legacyName) : value;
    }

    private enum JaegerMode {
        V1("v1", true, false), V2("v2", false, true), DUAL("dual", true, true);

        private final String externalName;
        private final boolean v1;
        private final boolean v2;

        JaegerMode(String externalName, boolean v1, boolean v2) {
            this.externalName = externalName;
            this.v1 = v1;
            this.v2 = v2;
        }

        boolean v1() { return v1; }
        boolean v2() { return v2; }

        static JaegerMode parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "v1" -> V1;
                case "v2" -> V2;
                case "dual" -> DUAL;
                default -> throw new IllegalArgumentException("JAEGER_MODE must be v1, v2, or dual");
            };
        }
    }
}
