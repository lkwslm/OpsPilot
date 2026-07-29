package io.github.opspilot.runtime.agentscope;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PostgresAgentStateStoreTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";

    @Test
    void restoresTwoIsolatedSessionsAfterRealJvmRestart() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("postgres")
                .withPassword("phase0-test-only")) {
            postgres.start();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute(resource("/db/bootstrap/roles.sql"));
            }
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load().migrate();

            String saveOutput = runChild(
                    "save", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            String restoreOutput = runChild(
                    "verify", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

            assertTrue(saveOutput.contains("SAVED:session-a,session-b,journal"), saveOutput);
            assertTrue(restoreOutput.contains(
                    "RESTORED:session-a=checkpoint-a,session-b=checkpoint-b;"
                            + "JOURNAL:usage-and-events;ISOLATED:true"),
                    restoreOutput);
        }
    }

    private static String resource(String name) throws IOException {
        try (var stream = PostgresAgentStateStoreTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String runChild(String mode, String jdbcUrl, String username, String password)
            throws IOException, InterruptedException {
        String javaExecutable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString();
        List<String> command = new ArrayList<>(List.of(
                java,
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Duser.home=" + System.getProperty("user.home"),
                "-cp", System.getProperty("java.class.path"),
                AgentStateStoreProcessProbe.class.getName(),
                mode, jdbcUrl, username, password, "server-agent-1"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
