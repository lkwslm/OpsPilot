package io.github.opspilot.adapters.observability;

import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_SCHEMA_INVALID;
import static org.junit.jupiter.api.Assertions.*;

final class Phase4ObservabilityAdapterTest {
    @TempDir Path temporary;

    @Test
    void independentHttpHealthAndControlledFileConfigUseSameBatchContract() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health");
            var health = new HttpHealthAdapter(uri).query(query("health/readiness-v1", SignalType.HEALTH), context());
            assertEquals("UP", health.observations().getFirst().attributes().get("status"));

            Path config = temporary.resolve("config.json");
            Files.writeString(config, "{\"server.port\":\"8080\",\"spring.application.name\":\"order-service\"}");
            var snapshot = new ControlledConfigAdapter(config,
                    Set.of("server.port", "spring.application.name"))
                    .query(query("config/allowlist-v1", SignalType.CONFIG), context());
            assertEquals(1, snapshot.observations().size());
            assertEquals("8080", snapshot.observations().getFirst().attributes().get("server.port"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configAdapterRejectsUnknownSensitiveAndOversizeSnapshotsBeforeTheyBecomeObservations() throws Exception {
        Path unknown = temporary.resolve("unknown.json");
        Files.writeString(unknown, "{\"server.port\":\"8080\",\"database.password\":\"leak\"}");
        SourceFailure failure = assertThrows(SourceFailure.class, () -> new ControlledConfigAdapter(
                unknown, Set.of("server.port")).query(query("config/allowlist-v1", SignalType.CONFIG), context()));
        assertEquals(SOURCE_SCHEMA_INVALID, failure.code());
        assertThrows(IllegalArgumentException.class,
                () -> new ControlledConfigAdapter(unknown, Set.of("database.password")));

        Path oversized = temporary.resolve("oversized.json");
        Files.writeString(oversized, "{\"server.port\":\"" + "a".repeat(70_000) + "\"}");
        assertEquals(SOURCE_SCHEMA_INVALID, assertThrows(SourceFailure.class, () -> new ControlledConfigAdapter(
                oversized, Set.of("server.port")).query(query("config/allowlist-v1", SignalType.CONFIG), context())).code());
    }

    @Test
    void arbitraryTemplateIsRejectedBeforeIoAndFailuresMapToStableChainFailure() throws Exception {
        Path missing = temporary.resolve("missing.jsonl");
        JsonlLogAdapter adapter = new JsonlLogAdapter(missing);
        SourceFailure invalid = assertThrows(SourceFailure.class,
                () -> adapter.query(query("https://attacker.invalid/?promql=up", SignalType.LOG), context()));
        assertEquals(SOURCE_SCHEMA_INVALID, invalid.code());

        SourceFailure unavailable = assertThrows(SourceFailure.class,
                () -> adapter.query(query("log/errors-v1", SignalType.LOG), context()));
        assertEquals("SOURCE_IO_FAILURE", unavailable.chainFailure(java.util.UUID.randomUUID()).errorCode());
    }

    @Test
    void connectionRefIsResolvedOnlyAtExecutionBoundaryAndFailsClosedAcrossTargets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
            String connectionRef = "observability-source://sample/http-health";
            var resolver = new ControlledSecretResolver(Map.of(connectionRef,
                    new ControlledSecretResolver.Binding("sample-commerce", endpoint, "token-value".toCharArray())));
            var adapter = SecretResolvingSourceAdapter.httpHealth(resolver, "sample-commerce");

            ObservationBatch batch = adapter.query(query("health/readiness-v1", SignalType.HEALTH), context());
            assertEquals(connectionRef, batch.source().connectionRef());
            assertFalse(batch.toString().contains(endpoint));
            assertFalse(batch.toString().contains("token-value"));

            var resolved = resolver.resolve(connectionRef,
                    new io.github.opspilot.core.port.observability.SecretResolver.ResolutionContext(
                            "test", "sample-commerce", true));
            assertEquals("ResolvedConnection[REDACTED]", resolved.toString());
            resolved.close();
            assertArrayEquals(new char["token-value".length()], resolved.secret());

            assertThrows(ControlledSecretResolver.SecretResolutionException.class,
                    () -> resolver.resolve(connectionRef,
                            new io.github.opspilot.core.port.observability.SecretResolver.ResolutionContext(
                                    "test", "other-target", true)));
            assertThrows(ControlledSecretResolver.SecretResolutionException.class,
                    () -> resolver.resolve(connectionRef,
                            new io.github.opspilot.core.port.observability.SecretResolver.ResolutionContext(
                                    "test", "sample-commerce", false)));
        } finally {
            server.stop(0);
        }
    }

    private static ObservationQuery query(String template, SignalType signal) {
        ResourceRef resource = new ResourceRef("service:order-service", ResourceType.SERVICE,
                "sample-commerce", "order-service", "local", Map.of());
        return new ObservationQuery(template, ObservationContracts.sha256("parameters"),
                Instant.now().minusSeconds(30), Instant.now().plusSeconds(30), resource);
    }

    private static SourceExecutionContext context() {
        return SourceExecutionContext.authorizedUntil(Instant.now().plusSeconds(5));
    }
}
