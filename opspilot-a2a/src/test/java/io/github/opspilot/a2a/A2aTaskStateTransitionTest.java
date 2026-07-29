package io.github.opspilot.a2a;

import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class A2aTaskStateTransitionTest {

    @Test
    void interruptedStatesResumeAndInvalidOrTerminalTransitionsAreRejected() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(A2aPostgresFixture.IMAGE)
                .withDatabaseName("opspilot")
                .withUsername("opspilot")
                .withPassword("phase6-test-only")) {
            postgres.start();
            A2aPostgresFixture.migrate(postgres);
            try (PostgresA2aTaskStore store = new PostgresA2aTaskStore(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                String taskId = store.create(new A2aSendRequest(
                        "state-message", "state-run", "state", true)).task().taskId();
                assertEquals(A2aTaskState.INPUT_REQUIRED,
                        store.transitionTo(taskId, A2aTaskState.INPUT_REQUIRED, null).state());
                assertEquals(A2aTaskState.WORKING,
                        store.transitionTo(taskId, A2aTaskState.WORKING, null).state());
                assertEquals(A2aTaskState.AUTH_REQUIRED,
                        store.transitionTo(taskId, A2aTaskState.AUTH_REQUIRED, null).state());
                assertEquals(A2aTaskState.WORKING,
                        store.transitionTo(taskId, A2aTaskState.WORKING, null).state());
                assertEquals(A2aTaskState.FAILED,
                        store.transitionTo(taskId, A2aTaskState.FAILED, null).state());
                assertThrows(IllegalStateException.class,
                        () -> store.transitionTo(taskId, A2aTaskState.WORKING, null));

                String invalidId = store.create(new A2aSendRequest(
                        "invalid-state-message", "state-run", "state", true)).task().taskId();
                assertThrows(IllegalStateException.class,
                        () -> store.transitionTo(invalidId, A2aTaskState.UNSPECIFIED, null));
                assertEquals(A2aTaskState.SUBMITTED, store.get(invalidId).orElseThrow().state());
            }
        }
    }
}
