package io.github.opspilot.a2a;

import io.github.opspilot.a2a.client.Phase0A2aClient;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.a2a.server.Phase0A2aServer;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aLifecycleRaceTest {

    private static final String IMAGE = A2aPostgresFixture.IMAGE;

    @Test
    void terminalStateSurvivesDisconnectDuplicateCancelRaceAndRestart() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            A2aSendRequest deferred = new A2aSendRequest(
                    "message-race", "context-race", "race-result", true);
            String racedTaskId;
            A2aTaskState terminalState;

            try (PostgresA2aTaskStore store = store(postgres);
                 Phase0A2aServer server = new Phase0A2aServer(store, 0)) {
                server.start();
                try (Phase0A2aClient client = client(server)) {
                    A2aTask submitted = client.send(deferred);
                    racedTaskId = submitted.taskId();
                    store.markWorking(racedTaskId);

                    try (var executor = Executors.newFixedThreadPool(2)) {
                        List<Callable<String>> racers = List.of(
                                () -> outcome(() -> client.cancel(racedTaskId)),
                                () -> outcome(() -> store.complete(racedTaskId, "race-result")));
                        List<String> outcomes = executor.invokeAll(racers).stream()
                                .map(future -> {
                                    try {
                                        return future.get();
                                    } catch (Exception exception) {
                                        throw new AssertionError(exception);
                                    }
                                })
                                .toList();
                        assertEquals(1, outcomes.stream().filter("SUCCESS"::equals).count());
                        assertEquals(1, outcomes.stream()
                                .filter(value -> value.contains("TASK_TERMINAL"))
                                .count());
                    }

                    A2aTask terminal = client.get(racedTaskId);
                    terminalState = terminal.state();
                    assertTrue(terminalState.terminal());
                    assertEquals(3, terminal.revision());
                    assertEquals(3, store.eventCount(racedTaskId));

                    assertTerminal(() -> store.markWorking(racedTaskId));
                    assertTerminal(() -> store.complete(racedTaskId, "late"));
                    assertTerminal(() -> store.cancel(racedTaskId));
                    assertEquals(terminal, client.get(racedTaskId));
                    assertEquals(3, store.eventCount(racedTaskId));

                    A2aTask repeated = client.send(deferred);
                    assertEquals(racedTaskId, repeated.taskId());
                    assertEquals(terminalState, repeated.state());
                    assertEquals(3, store.eventCount(racedTaskId));

                    assertEquals(1, client.stream(new A2aSendRequest(
                            "message-disconnect-race", "context-race", "disconnect", false), 1).size());
                }
            }

            try (PostgresA2aTaskStore restartedStore = store(postgres);
                 Phase0A2aServer restartedServer = new Phase0A2aServer(restartedStore, 0)) {
                restartedServer.start();
                try (Phase0A2aClient restartedClient = client(restartedServer)) {
                    A2aTask recovered = restartedClient.get(racedTaskId);
                    assertEquals(terminalState, recovered.state());
                    assertEquals(3, recovered.revision());
                    assertEquals(3, restartedStore.eventCount(racedTaskId));
                }
            }
        }
    }

    private static String outcome(Action action) {
        try {
            action.run();
            return "SUCCESS";
        } catch (RuntimeException exception) {
            return exception.getMessage();
        }
    }

    private static void assertTerminal(Action action) {
        IllegalStateException terminal = assertThrows(IllegalStateException.class, action::run);
        assertTrue(terminal.getMessage().contains("TASK_TERMINAL"));
    }

    private static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("opspilot")
                .withPassword("phase0-test-only");
    }

    private static PostgresA2aTaskStore store(PostgreSQLContainer postgres) {
        return new PostgresA2aTaskStore(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Phase0A2aClient client(Phase0A2aServer server) {
        return new Phase0A2aClient(URI.create("http://127.0.0.1:" + server.port() + "/"));
    }

    @FunctionalInterface
    private interface Action {
        void run();
    }
}
