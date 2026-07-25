package io.github.opspilot.faultlab;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class DockerCliContainerControlAdapter {
    private final String composeProject;
    private final CommandRunner runner;

    public DockerCliContainerControlAdapter(String composeProject) {
        this(composeProject, CommandRunner.system());
    }

    DockerCliContainerControlAdapter(String composeProject, CommandRunner runner) {
        if (composeProject == null || !composeProject.matches("[A-Za-z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("Invalid Compose project");
        }
        this.composeProject = composeProject;
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public void stopInventory() {
        run("stop", "inventory-service");
    }

    public void startInventory() {
        run("start", "inventory-service");
    }

    private void run(String operation, String service) {
        List<String> command = List.of("docker", "compose", "--project-name", composeProject,
                "-f", "/workspace/deployment/docker-compose.yml", operation, service);
        int exit = runner.run(command, Duration.ofSeconds(30));
        if (exit != 0) throw new IllegalStateException("FAULT_LAB_CONTAINER_CONTROL_FAILED");
    }

    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command, Duration timeout);

        static CommandRunner system() {
            return (command, timeout) -> {
                try {
                    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        return 124;
                    }
                    return process.exitValue();
                } catch (IOException exception) {
                    throw new IllegalStateException("FAULT_LAB_DOCKER_CLI_UNAVAILABLE", exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return 130;
                }
            };
        }
    }
}
