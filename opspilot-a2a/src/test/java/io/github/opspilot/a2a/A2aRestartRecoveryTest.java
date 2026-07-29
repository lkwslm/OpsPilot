package io.github.opspilot.a2a;

import io.github.opspilot.a2a.client.Phase0A2aClient;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskEvent;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.a2a.server.Phase0A2aServer;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class A2aRestartRecoveryTest {

    private static final String IMAGE = A2aPostgresFixture.IMAGE;

    @Test
    void replaysStreamAndArtifactAfterDisconnectAndServerClientRestart() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            String taskId;

            try (PostgresA2aTaskStore store = store(postgres);
                 Phase0A2aServer server = new Phase0A2aServer(store, 0)) {
                server.start();
                try (Phase0A2aClient client = client(server)) {
                    List<A2aTaskEvent> disconnected = client.stream(
                            new A2aSendRequest("message-restart", "context-restart", "recovered", false),
                            1);
                    assertEquals(1, disconnected.size());
                    assertEquals(A2aTaskState.SUBMITTED, disconnected.getFirst().task().state());
                    taskId = disconnected.getFirst().task().taskId();
                }
            }

            try (PostgresA2aTaskStore restartedStore = store(postgres);
                 Phase0A2aServer restartedServer = new Phase0A2aServer(restartedStore, 0)) {
                restartedServer.start();
                try (Phase0A2aClient restartedClient = client(restartedServer)) {
                    A2aTask recovered = restartedClient.get(taskId);
                    assertEquals(A2aTaskState.COMPLETED, recovered.state());
                    assertNotNull(recovered.artifact());
                    assertEquals(
                            "application/vnd.opspilot.incident-investigation.result+json;v=1",
                            recovered.artifact().mediaType());
                    assertEquals("{\"result\":\"recovered\"}", recovered.artifact().payload());

                    List<A2aTaskEvent> replay = restartedClient.subscribe(taskId, 1);
                    assertEquals(List.of(A2aTaskState.WORKING, A2aTaskState.COMPLETED),
                            replay.stream().map(event -> event.task().state()).toList());
                    assertNotNull(replay.getLast().task().artifact());

                    A2aTask deferred = restartedClient.send(new A2aSendRequest(
                            "message-cancel", "context-cancel", "wait", true));
                    assertEquals(A2aTaskState.SUBMITTED, deferred.state());
                    assertEquals(A2aTaskState.CANCELED, restartedClient.cancel(deferred.taskId()).state());
                }
            }
        }
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

}
