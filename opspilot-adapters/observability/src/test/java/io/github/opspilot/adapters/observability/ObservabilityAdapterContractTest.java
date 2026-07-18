package io.github.opspilot.adapters.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilitySourceAdapter;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.opspilot.adapters.observability.SourceFailure.Code.ARTIFACT_HASH_MISMATCH;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_AUTH_FAILED;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_CANCELLED;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_SCHEMA_INVALID;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservabilityAdapterContractTest {
    private static final String FIXTURE_ROOT = "/fixtures/observability/";
    private static HttpServer server;
    private static URI baseUri;

    @BeforeAll
    static void startReplayServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        addFixtureEndpoint("/prometheus/success", "prometheus-success.json", 200);
        addFixtureEndpoint("/prometheus/empty", "prometheus-empty.json", 200);
        addFixtureEndpoint("/prometheus/invalid", "prometheus-invalid.json", 200);
        addFixtureEndpoint("/jaeger/success", "jaeger-success.json", 200);
        addFixtureEndpoint("/jaeger/empty", "jaeger-empty.json", 200);
        addFixtureEndpoint("/jaeger/invalid", "jaeger-invalid.json", 200);
        server.createContext("/unauthorized", exchange -> respond(exchange, 401, new byte[0]));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 200, fixtureBytes("prometheus-success.json"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void stopReplayServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fiveRealAdaptersPassTheSameSuccessEmptyFailureAndIntegrityContract() throws Exception {
        Set<String> sourceIds = new HashSet<>();
        for (AdapterKind kind : AdapterKind.values()) {
            ObservabilitySourceAdapter successAdapter = adapter(kind, "success");
            assertTrue(sourceIds.add(successAdapter.descriptor().sourceId()));
            assertEquals(successAdapter.descriptor(), successAdapter.descriptor());
            assertEquals("1.0.0", successAdapter.descriptor().adapterVersion());
            assertTrue(successAdapter.descriptor().connectionRef().startsWith("observability-source://"));

            ObservationBatch batch = successAdapter.query(query(), authorized(null));
            assertBatchContract(batch, successAdapter);
            assertFalse(batch.observations().isEmpty());
            assertTrue(batch.observations().stream().noneMatch(record ->
                    record.summary().contains("super-secret") || record.summary().contains("Bearer-secret-value")));

            ObservationBatch empty = adapter(kind, "empty").query(query(), authorized(null));
            assertBatchContract(empty, adapter(kind, "empty"));
            assertTrue(empty.observations().isEmpty());

            assertFailure(adapter(kind, "invalid"), authorized(null), SOURCE_SCHEMA_INVALID);
            assertFailure(successAdapter,
                    new SourceExecutionContext(Instant.now().minusSeconds(1), true, () -> false, null),
                    SOURCE_TIMEOUT);
            assertFailure(successAdapter,
                    new SourceExecutionContext(Instant.now().plusSeconds(5), false, () -> false, null),
                    SOURCE_AUTH_FAILED);
            assertFailure(successAdapter,
                    new SourceExecutionContext(Instant.now().plusSeconds(5), true, () -> true, null),
                    SOURCE_CANCELLED);
            assertFailure(successAdapter, authorized("sha256:" + "0".repeat(64)), ARTIFACT_HASH_MISMATCH);
        }
        assertEquals(5, sourceIds.size());
    }

    @Test
    void httpAdapterMapsRealAuthenticationAndTimeoutFailuresWithoutFallback() {
        ObservabilitySourceAdapter unauthorized = new PrometheusMetricAdapter(baseUri.resolve("/unauthorized"));
        SourceFailure auth = assertThrows(SourceFailure.class,
                () -> unauthorized.query(query(), authorized(null)));
        assertEquals(SOURCE_AUTH_FAILED, auth.code());
        assertEquals("phase0-prometheus", auth.sourceId());

        ObservabilitySourceAdapter slow = new PrometheusMetricAdapter(baseUri.resolve("/slow"));
        SourceFailure timeout = assertThrows(SourceFailure.class, () -> slow.query(query(),
                new SourceExecutionContext(Instant.now().plusMillis(100), true, () -> false, null)));
        assertEquals(SOURCE_TIMEOUT, timeout.code());
        assertEquals("phase0-prometheus", timeout.sourceId());
    }

    @Test
    void evidenceIsImmutableAndTracesBackToBatchRecordSourceResourceAndHashedArtifact() throws Exception {
        List<ObservationBatch> batches = new ArrayList<>();
        for (AdapterKind kind : AdapterKind.values()) {
            batches.add(adapter(kind, "success").query(query(), authorized(null)));
        }
        var context = new NormalizationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(batches, context);

        assertEquals(5, bundle.observationBatchIds().size());
        assertEquals(batches.stream().mapToInt(batch -> batch.observations().size()).sum(), bundle.evidence().size());
        assertThrows(UnsupportedOperationException.class,
                () -> bundle.observationBatchIds().add(UUID.randomUUID()));

        bundle.evidence().forEach(evidence -> {
            assertEquals("RUNTIME", evidence.factOrigin());
            assertEquals(1, evidence.provenanceRefs().size());
            var provenance = evidence.provenanceRefs().getFirst();
            ObservationBatch batch = batches.stream()
                    .filter(candidate -> candidate.batchId().equals(provenance.batchId()))
                    .findFirst().orElseThrow();
            var record = batch.observations().stream()
                    .filter(candidate -> candidate.observationId().equals(provenance.observationId()))
                    .findFirst().orElseThrow();
            assertEquals(batch.source().sourceId(), provenance.sourceId());
            assertEquals(record.resource(), evidence.resource());
            assertEquals(record.artifactId(), evidence.artifactIds().getFirst());
            assertEquals(batch.rawArtifact().sha256(), provenance.artifactSha256());
            assertEquals(batch.rawArtifact().sha256(),
                    ObservationContracts.sha256(batch.rawArtifact().content()));
        });
    }

    @Test
    void agentRcaAndEvaluationSourcesDoNotDependOnVendorDtosOrObservationBatches() throws Exception {
        Path root = projectRoot();
        for (String module : List.of("opspilot-agent-runtime-agentscope", "opspilot-server", "opspilot-evaluation")) {
            Path source = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) {
                continue;
            }
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String java = Files.readString(file);
                    assertFalse(java.contains("io.prometheus"), file + " depends on Prometheus DTOs");
                    assertFalse(java.contains("io.jaegertracing"), file + " depends on Jaeger DTOs");
                    assertFalse(java.contains("org.springframework.boot.actuate"), file + " depends on Actuator DTOs");
                    assertFalse(java.contains("ObservationBatch"), file + " bypasses EvidenceNormalizer");
                }
            }
        }
    }

    private static void assertBatchContract(ObservationBatch batch, ObservabilitySourceAdapter adapter) {
        assertEquals("1.0.0", batch.schemaVersion());
        assertEquals(adapter.descriptor(), batch.source());
        assertTrue(batch.query().parameterHash().matches("sha256:[a-f0-9]{64}"));
        assertEquals(ObservationContracts.sha256(batch.rawArtifact().content()), batch.rawArtifact().sha256());
        batch.observations().forEach(record -> {
            assertEquals(batch.rawArtifact().artifactId(), record.artifactId());
            assertEquals(batch.query().resource(), record.resource());
            assertTrue(batch.source().capabilities().contains(record.signalType()));
        });
    }

    private static void assertFailure(ObservabilitySourceAdapter adapter,
                                      SourceExecutionContext context, SourceFailure.Code expected) {
        SourceFailure failure = assertThrows(SourceFailure.class, () -> adapter.query(query(), context));
        assertEquals(expected, failure.code());
        assertEquals(adapter.descriptor().sourceId(), failure.sourceId());
    }

    private static ObservabilitySourceAdapter adapter(AdapterKind kind, String mode) throws Exception {
        return switch (kind) {
            case PROMETHEUS -> new PrometheusMetricAdapter(baseUri.resolve("/prometheus/" + mode));
            case JAEGER -> new JaegerTraceAdapter(baseUri.resolve("/jaeger/" + mode));
            case JSONL -> new JsonlLogAdapter(fixturePath("jsonl-" + mode + ".jsonl"));
            case ACTUATOR -> new SpringActuatorHealthAdapter(fixturePath("actuator-" + mode + ".json"));
            case COMPOSE -> new StaticComposeTopologyAdapter(fixturePath("compose-" + mode + ".yaml"));
        };
    }

    private static ObservationQuery query() {
        ResourceRef resource = new ResourceRef(
                "service:sample-system", ResourceType.SERVICE, "sample-system", "sample-system",
                "phase0", Map.of("namespace", "opspilot"));
        return new ObservationQuery(
                "phase0/replay", ObservationContracts.sha256("service=sample-system"),
                Instant.parse("2026-07-18T07:55:00Z"), Instant.parse("2026-07-18T08:05:00Z"), resource);
    }

    private static SourceExecutionContext authorized(String expectedHash) {
        return new SourceExecutionContext(Instant.now().plusSeconds(10), true, () -> false, expectedHash);
    }

    private static void addFixtureEndpoint(String path, String fixture, int status) {
        server.createContext(path, exchange -> respond(exchange, status, fixtureBytes(fixture)));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (var stream = ObservabilityAdapterContractTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            if (stream == null) {
                throw new IOException("Missing fixture " + name);
            }
            return stream.readAllBytes();
        }
    }

    private static Path fixturePath(String name) throws Exception {
        return Path.of(ObservabilityAdapterContractTest.class.getResource(FIXTURE_ROOT + name).toURI());
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deployment/versions.lock.yaml"))
                    && Files.isDirectory(current.resolve("opspilot-core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("OpsPilot project root not found");
    }

    private enum AdapterKind { PROMETHEUS, JAEGER, JSONL, ACTUATOR, COMPOSE }
}
