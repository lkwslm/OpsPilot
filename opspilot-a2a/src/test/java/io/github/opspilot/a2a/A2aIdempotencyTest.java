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
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class A2aIdempotencyTest {

    private static final String IMAGE = A2aPostgresFixture.IMAGE;

    @Test
    void persistsCrossBoundaryCorrelationOnTaskAndEveryEvent() throws Exception {
        try (PostgreSQLContainer postgres = postgres()) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            UUID incidentId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            UUID traceId = UUID.randomUUID();
            UUID stepId = UUID.randomUUID();
            UUID invocationId = UUID.randomUUID();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("INSERT INTO opspilot.target_system (target_system_id,display_name) "
                        + "VALUES ('a2a-correlation','A2A correlation')");
                statement.execute("INSERT INTO opspilot.incident (incident_id,target_system_id,status) VALUES ('"
                        + incidentId + "','a2a-correlation','OPEN')");
                statement.execute("INSERT INTO opspilot.incident_run (run_id,incident_id,status) VALUES ('"
                        + runId + "','" + incidentId + "','CREATED')");
            }
            try (PostgresA2aTaskStore store = store(postgres)) {
                A2aSendRequest request = new A2aSendRequest(
                        "message-correlation", runId.toString(), "payload", true,
                        "svc:opspilot-server", "evidence-collector", "collect-observability-evidence",
                        "application/json", "application/json", "1.0",
                        List.of("urn:opspilot:a2a:correlation:v1"), List.of(),
                        requestId.toString(), traceId.toString(), runId.toString(), stepId.toString(),
                        null, invocationId.toString());
                A2aTask task = store.create(request).task();
                store.markWorking(task.taskId());

                try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                     var statement = connection.createStatement()) {
                    try (var result = statement.executeQuery("SELECT run_id,request_id,trace_id,step_id,invocation_id "
                            + "FROM opspilot_a2a.task WHERE task_id='" + task.taskId() + "'")) {
                        assertTrue(result.next());
                        assertEquals(runId, result.getObject("run_id", UUID.class));
                        assertEquals(requestId, result.getObject("request_id", UUID.class));
                        assertEquals(traceId, result.getObject("trace_id", UUID.class));
                        assertEquals(stepId, result.getObject("step_id", UUID.class));
                        assertEquals(invocationId, result.getObject("invocation_id", UUID.class));
                    }
                    try (var result = statement.executeQuery("SELECT count(*) FROM opspilot_a2a.task_event "
                            + "WHERE task_id='" + task.taskId() + "' AND run_id='" + runId + "'")) {
                        assertTrue(result.next());
                        assertEquals(2, result.getInt(1));
                    }
                }
            }
        }
    }

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
