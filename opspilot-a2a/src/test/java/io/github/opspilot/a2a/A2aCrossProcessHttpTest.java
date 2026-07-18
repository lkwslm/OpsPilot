package io.github.opspilot.a2a;

import io.github.opspilot.a2a.client.Phase0A2aClient;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskState;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aCrossProcessHttpTest {

    private static final String IMAGE = "pgvector/pgvector:pg16";

    @Test
    void capturesRealHttpJsonAcrossProcessesAndFailsWhenNetworkIsDisabled() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            int port = availablePort();
            Process child = startServerProcess(postgres, port);
            List<String> childOutput = new ArrayList<>();
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String ready = readUntilReady(output, childOutput);
                long childPid = Long.parseLong(ready.split("processId=")[1].split(" ")[0]);
                assertNotEquals(ProcessHandle.current().pid(), childPid);

                URI endpoint = URI.create("http://127.0.0.1:" + port + "/");
                try (Phase0A2aClient client = new Phase0A2aClient(endpoint)) {
                    A2aTask task = client.send(new A2aSendRequest(
                            "message-cross-process", "context-cross-process", "http-json", false));
                    assertEquals(A2aTaskState.COMPLETED, task.state());
                    assertEquals(task, client.get(task.taskId()));
                }

                child.getOutputStream().close();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                output.lines().forEach(childOutput::add);
                assertEquals(0, child.exitValue(), String.join("\n", childOutput));

                assertTrue(childOutput.stream().anyMatch(line ->
                        line.contains("method=POST")
                                && line.contains("path=/a2a/messages:send")
                                && line.contains("contentType=application/json")));
                assertTrue(childOutput.stream().anyMatch(line ->
                        line.contains("method=GET") && line.contains("path=/a2a/tasks/")));
                writeAuditEvidence(childOutput);

                try (Phase0A2aClient disconnected = new Phase0A2aClient(endpoint)) {
                    IllegalStateException failure = assertThrows(IllegalStateException.class,
                            () -> disconnected.send(new A2aSendRequest(
                                    "message-network-off", "context-network-off", "fail", false)));
                    assertTrue(failure.getMessage().contains("A2A_NETWORK_FAILURE"));
                }
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        }
    }

    private static void writeAuditEvidence(List<String> childOutput) throws IOException {
        String configuredPath = System.getProperty("a2a.audit.log");
        if (configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        Path output = Path.of(configuredPath).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.write(output, childOutput.stream()
                .filter(line -> line.startsWith("READY ") || line.startsWith("HTTP_EXCHANGE "))
                .toList(), StandardCharsets.UTF_8);
    }

    private static String readUntilReady(BufferedReader output, List<String> captured)
            throws IOException {
        String line;
        while ((line = output.readLine()) != null) {
            captured.add(line);
            if (line.startsWith("READY ")) {
                return line;
            }
        }
        throw new AssertionError("Child server exited before READY: " + String.join("\n", captured));
    }

    private static Process startServerProcess(PostgreSQLContainer postgres, int port)
            throws IOException {
        String javaExecutable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaExecutable).toString();
        return new ProcessBuilder(
                java,
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Duser.home=" + System.getProperty("user.home"),
                "-cp", System.getProperty("java.class.path"),
                A2aServerProcessProbe.class.getName(),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                Integer.toString(port))
                .redirectErrorStream(true)
                .start();
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("opspilot")
                .withPassword("phase0-test-only");
    }
}
