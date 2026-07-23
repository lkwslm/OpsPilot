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
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aIdempotencyTest {

    private static final String IMAGE = A2aPostgresFixture.IMAGE;

    @Test
    void returnsOriginalTaskWithoutDuplicateEventsAndRejectsHashConflict() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            try (PostgresA2aTaskStore store = store(postgres);
                 Phase0A2aServer server = new Phase0A2aServer(store, 0)) {
                server.start();
                try (Phase0A2aClient client = client(server)) {
                    A2aSendRequest request = new A2aSendRequest(
                            "message-idempotent", "context-idempotent", "same payload", false);
                    A2aTask first = client.send(request);
                    A2aTask repeated = client.send(request);

                    assertEquals(first.taskId(), repeated.taskId());
                    assertEquals(A2aTaskState.COMPLETED, repeated.state());
                    assertEquals(3, store.eventCount(first.taskId()));

                    IllegalStateException conflict = assertThrows(IllegalStateException.class,
                            () -> client.send(new A2aSendRequest(
                                    request.messageId(), request.contextId(), "different payload", false)));
                    assertTrue(conflict.getMessage().contains("MESSAGE_ID_HASH_CONFLICT"));
                    assertEquals(3, store.eventCount(first.taskId()));
                }
            }
        }
    }

    @Test
    void concurrentDuplicateSendCreatesOneTaskAndOneSetOfSideEffects() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            try (PostgresA2aTaskStore store = store(postgres);
                 Phase0A2aServer server = new Phase0A2aServer(store, 0)) {
                server.start();
                A2aSendRequest request = new A2aSendRequest(
                        "message-concurrent", "context-concurrent", "same payload", false);
                try (var executor = Executors.newFixedThreadPool(8)) {
                    List<Callable<A2aTask>> calls = java.util.stream.IntStream.range(0, 16)
                            .mapToObj(index -> (Callable<A2aTask>) () -> {
                                try (Phase0A2aClient client = client(server)) {
                                    return client.send(request);
                                }
                            })
                            .toList();
                    List<Future<A2aTask>> futures = executor.invokeAll(calls);
                    List<A2aTask> tasks = futures.stream().map(A2aIdempotencyTest::result).toList();
                    String taskId = tasks.getFirst().taskId();
                    assertTrue(tasks.stream().allMatch(task -> task.taskId().equals(taskId)));
                    assertEquals(A2aTaskState.COMPLETED, store.get(taskId).orElseThrow().state());
                    assertEquals(3, store.eventCount(taskId));
                }
            }
        }
    }

    private static A2aTask result(Future<A2aTask> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
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
