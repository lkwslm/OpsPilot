package io.github.opspilot.a2a;

import io.github.opspilot.a2a.contract.A2aArtifact;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.a2a.server.PostgresDomainArtifactWriter;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aArtifactBoundaryTest {

    private static final String IMAGE = "pgvector/pgvector:pg16";
    private static final String APP_USER = "phase0_domain_app";
    private static final String AGENT_USER = "phase0_specialist_agent";
    private static final String ROLE_PASSWORD = "phase0-role-test-only";

    @Test
    void validatesEveryArtifactBoundaryBeforeDomainWrite() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            createDomainBoundary(postgres);
            PostgresDomainArtifactWriter writer = new PostgresDomainArtifactWriter(
                    postgres.getJdbcUrl(), APP_USER, ROLE_PASSWORD);
            A2aTask valid = task(artifact("application/json", "1.0.0", "{\"ok\":true}", null));

            writer.write(valid, valid.taskId(), "diagnosis", "diagnosis", Set.of("diagnosis"));
            assertEquals(1, rowCount(postgres));

            assertRejected(writer, task(artifact("text/plain", "1.0.0", "{}", null)),
                    "task-1", "diagnosis", "diagnosis", Set.of("diagnosis"),
                    "ARTIFACT_MEDIA_TYPE_UNSUPPORTED");
            assertRejected(writer, task(artifact("application/json", "2.0.0", "{}", null)),
                    "task-1", "diagnosis", "diagnosis", Set.of("diagnosis"),
                    "ARTIFACT_SCHEMA_MAJOR_UNSUPPORTED");
            assertRejected(writer, task(artifact("application/json", "1.0.0", "{}", "bad-hash")),
                    "task-1", "diagnosis", "diagnosis", Set.of("diagnosis"),
                    "ARTIFACT_SHA256_MISMATCH");
            assertRejected(writer, valid, "another-task", "diagnosis", "diagnosis",
                    Set.of("diagnosis"), "ARTIFACT_TASK_ID_MISMATCH");
            assertRejected(writer, valid, valid.taskId(), "knowledge", "diagnosis",
                    Set.of("knowledge"), "ARTIFACT_CALLER_IDENTITY_MISMATCH");
            assertRejected(writer, valid, valid.taskId(), "diagnosis", "diagnosis",
                    Set.of("knowledge"), "ARTIFACT_ACCESS_DENIED");
            assertEquals(1, rowCount(postgres));
        }
    }

    @Test
    void specialistAgentDatabaseRoleCannotWriteOpspilotDomainTable() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            createDomainBoundary(postgres);
            SQLException denied = assertThrows(SQLException.class, () -> {
                try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), AGENT_USER, ROLE_PASSWORD);
                     var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO opspilot.domain_artifact "
                            + "(task_id, artifact_id, media_type, schema_version, sha256, payload) "
                            + "VALUES ('task', 'artifact', 'application/json', '1.0.0', 'hash', '{}'::jsonb)");
                }
            });
            assertTrue(denied instanceof PSQLException);
            assertEquals("42501", denied.getSQLState());
            assertEquals(0, rowCount(postgres));
        }
    }

    private static void assertRejected(
            PostgresDomainArtifactWriter writer,
            A2aTask task,
            String requestedTaskId,
            String caller,
            String expectedCaller,
            Set<String> authorized,
            String reason) throws SQLException {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> writer.write(task, requestedTaskId, caller, expectedCaller, authorized));
        assertEquals(reason, rejected.getMessage());
    }

    private static A2aTask task(A2aArtifact artifact) {
        return new A2aTask("task-1", "context-1", "message-1",
                A2aTaskState.COMPLETED, artifact, 3);
    }

    private static A2aArtifact artifact(
            String mediaType, String schemaVersion, String payload, String overriddenHash)
            throws Exception {
        String hash = overriddenHash == null ? sha256(payload) : overriddenHash;
        return new A2aArtifact("artifact-1", mediaType, schemaVersion, hash, payload);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("opspilot")
                .withPassword("phase0-test-only");
    }

    private static void createDomainBoundary(PostgreSQLContainer postgres) throws SQLException {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA opspilot");
            statement.execute("CREATE TABLE opspilot.domain_artifact ("
                    + "task_id TEXT NOT NULL, artifact_id TEXT PRIMARY KEY, media_type TEXT NOT NULL, "
                    + "schema_version TEXT NOT NULL, sha256 TEXT NOT NULL, payload JSONB NOT NULL)");
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            statement.execute("CREATE ROLE " + AGENT_USER + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            statement.execute("GRANT USAGE ON SCHEMA opspilot TO " + APP_USER + ", " + AGENT_USER);
            statement.execute("GRANT SELECT, INSERT ON opspilot.domain_artifact TO " + APP_USER);
            statement.execute("GRANT SELECT ON opspilot.domain_artifact TO " + AGENT_USER);
        }
    }

    private static long rowCount(PostgreSQLContainer postgres) throws SQLException {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM opspilot.domain_artifact")) {
            result.next();
            return result.getLong(1);
        }
    }
}
