package io.github.opspilot.runtime.agentscope;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PostgresAgentStateStoreTest {

    private static final String IMAGE = "pgvector/pgvector:pg16";
    private static final String EXPECTED_IMAGE_ID =
            "sha256:b295c2aa92725ecaaa58ffb6664035b45076318d8ca93ae4a9b0994481862f7d";

    @Test
    void restoresTwoIsolatedSessionsAfterRealJvmRestart() throws Exception {
        assertEquals(EXPECTED_IMAGE_ID, inspectLocalImageId());

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("opspilot")
                .withPassword("phase0-test-only")) {
            postgres.start();

            String saveOutput = runChild(
                    "save", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            String restoreOutput = runChild(
                    "verify", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

            assertTrue(saveOutput.contains("SAVED:session-a,session-b"), saveOutput);
            assertTrue(restoreOutput.contains(
                    "RESTORED:session-a=checkpoint-a,session-b=checkpoint-b;ISOLATED:true"),
                    restoreOutput);
        }
    }

    private static String inspectLocalImageId() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "docker", "image", "inspect", IMAGE, "--format", "{{.Id}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
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
