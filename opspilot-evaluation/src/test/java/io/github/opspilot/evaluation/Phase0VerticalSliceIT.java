package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase0VerticalSliceIT {
    private static final String IMAGE = "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static PostgreSQLContainer postgres;
    private static Path root;
    private static Path output;

    @BeforeAll
    static void startRealPostgres() throws Exception {
        root = projectRoot();
        assertTrue(root.startsWith(Path.of("E:\\").toAbsolutePath()), "Phase 0 output must remain on E drive");
        output = root.resolve("outputs/phase0/01-WP10");
        Files.createDirectories(output);
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot_wp10")
                .withUsername("postgres")
                .withPassword("phase0-test-only");
        postgres.start();
        assertEquals(IMAGE, postgres.getDockerImageName());
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(resource("/db/bootstrap/roles.sql"));
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
    }

    @AfterAll
    static void stopRealPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void realA2aSourceModelPersistenceAndDeterministicEvaluationAreTraceable() throws Exception {
        Phase0VerticalSlice useCase = new Phase0VerticalSlice(realConfig(
                URI.create("http://127.0.0.1:8080/a2a/messages:send"),
                URI.create("https://api.deepseek.com/chat/completions")));
        Phase0VerticalSlice.Result result;
        try (Connection connection = connection()) {
            result = useCase.run(connection);
            Phase0VerticalSlice.verifyPersistedGraph(connection, result);
            verifyEveryArtifactHash(connection, result.runId());
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM opspilot.evaluation_result WHERE evaluation_id='" + result.evaluationId() + "'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM opspilot_eval.ground_truth"));
        }
        assertTrue(Files.isRegularFile(result.reportPath()));
        assertEquals(64, result.evaluationArtifactSha256().length());
    }

    @Test
    void realFailuresAreFrozenAndNeverSwitchImplementation() throws Exception {
        Map<String, String> results = new LinkedHashMap<>();
        Phase0VerticalSlice real = new Phase0VerticalSlice(realConfig(
                URI.create("http://127.0.0.1:8080/a2a/messages:send"),
                URI.create("https://api.deepseek.com/chat/completions")));
        Phase0VerticalSlice.Failure source = assertThrows(Phase0VerticalSlice.Failure.class,
                () -> real.collectEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID().toString(), "source-unavailable"));
        assertEquals("SOURCE_UNAVAILABLE", source.code());
        results.put("source", source.code());

        Phase0VerticalSlice noA2aFallback = new Phase0VerticalSlice(realConfig(
                URI.create("http://127.0.0.1:1/a2a/messages:send"),
                URI.create("https://api.deepseek.com/chat/completions")));
        Phase0VerticalSlice.Failure a2a = assertThrows(Phase0VerticalSlice.Failure.class,
                () -> noA2aFallback.collectEvidence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID().toString(), null));
        assertEquals("A2A_UNAVAILABLE", a2a.code());
        results.put("a2a", a2a.code());

        Phase0VerticalSlice noModelFallback = new Phase0VerticalSlice(realConfig(
                URI.create("http://127.0.0.1:8080/a2a/messages:send"),
                URI.create("http://127.0.0.1:1/chat/completions")));
        try (Connection connection = connection()) {
            Phase0VerticalSlice.Failure model = assertThrows(
                    Phase0VerticalSlice.Failure.class, () -> noModelFallback.run(connection));
            assertEquals("MODEL_UNAVAILABLE", model.code());
            results.put("model", model.code());
        }

        Phase0VerticalSlice.Failure database = assertThrows(Phase0VerticalSlice.Failure.class,
                () -> Phase0VerticalSlice.connect("jdbc:postgresql://127.0.0.1:1/unavailable", "none", "none"));
        assertEquals("DATABASE_UNAVAILABLE", database.code());
        results.put("database", database.code());

        var report = JSON.createObjectNode();
        report.put("schemaVersion", "1.0.0");
        report.put("status", "PASSED");
        report.put("generatedAt", Instant.now().toString());
        report.put("realSource", "read-only JSONL through JsonlLogAdapter");
        report.put("realA2a", "HTTP+JSON across supervisor and evidence-agent containers");
        report.put("realModel", "deepseek-v4-flash; unreachable endpoint failed closed");
        report.put("realDatabase", IMAGE);
        report.put("mockOrFakeUsed", false);
        report.put("fixedRcaUsed", false);
        report.put("silentFallbackUsed", false);
        report.set("failureSemantics", JSON.valueToTree(results));
        Path reportPath = output.resolve("01-WP10.T04-failure-report.json");
        Files.writeString(reportPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        String serialized = Files.readString(reportPath);
        assertFalse(serialized.contains(System.getenv("DEEPSEEK_API_KEY")));
        assertFalse(serialized.contains("must-redact"));
    }

    private static Phase0VerticalSlice.Config realConfig(URI supervisorEndpoint, URI modelEndpoint) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertTrue(apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY must be loaded from E-drive secret file");
        String supervisorToken = Files.readString(
                root.resolve(".tmp/secrets/supervisor-service-token.txt"), StandardCharsets.UTF_8).strip();
        String configHash = Phase0VerticalSlice.sha256(
                Files.readAllBytes(root.resolve("deployment/versions.lock.yaml")));
        return new Phase0VerticalSlice.Config(supervisorEndpoint, supervisorToken, modelEndpoint, apiKey,
                "deepseek-v4-flash", root.resolve("deployment/agent-input/observability.jsonl"),
                output, configHash);
    }

    private static void verifyEveryArtifactHash(Connection connection, UUID runId) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT uri,trim(sha256) FROM opspilot.artifact WHERE run_id='" + runId + "'")) {
            int count = 0;
            while (rows.next()) {
                URI uri = URI.create(rows.getString(1));
                assertEquals("file", uri.getScheme());
                assertEquals(rows.getString(2), Phase0VerticalSlice.sha256(Files.readAllBytes(Path.of(uri))));
                count++;
            }
            assertEquals(5, count);
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Path projectRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("deployment/versions.lock.yaml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("PROJECT_ROOT_NOT_FOUND");
        }
        return candidate;
    }

    private static String resource(String path) throws Exception {
        try (var stream = Phase0VerticalSliceIT.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
