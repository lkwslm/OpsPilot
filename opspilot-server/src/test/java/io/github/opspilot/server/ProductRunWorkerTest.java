package io.github.opspilot.server;

import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository.LeasedTask;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductRunWorkerTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void completesTheLeasedProductRunAfterTheGatewaySucceeds() {
        FakeQueue queue = new FakeQueue(task(1, 3));
        List<UUID> executed = new ArrayList<>();
        try (var worker = worker(queue, leased -> executed.add(leased.runId()), (run, code) -> { })) {
            assertTrue(worker.drainOnce());
        }
        assertEquals(List.of(queue.task.runId()), executed);
        assertEquals(List.of("reconcile", "recover", "claim", "running", "complete"), queue.events);
    }

    @Test
    void reschedulesARecoverableAttemptWithoutPublishingAFinalFailure() {
        FakeQueue queue = new FakeQueue(task(1, 3));
        List<String> failures = new ArrayList<>();
        try (var worker = worker(queue,
                ignored -> { throw new ProductRunWorker.ProductRunFailure("A2A_TIMEOUT"); },
                (run, code) -> failures.add(code))) {
            assertTrue(worker.drainOnce());
        }
        assertTrue(failures.isEmpty());
        assertEquals(List.of("reconcile", "recover", "claim", "running", "retry"), queue.events);
        assertEquals(NOW.plusSeconds(2), queue.availableAt);
    }

    @Test
    void recordsTheStableFailureAndTerminatesTheLastAttempt() {
        FakeQueue queue = new FakeQueue(task(3, 3));
        List<String> failures = new ArrayList<>();
        try (var worker = worker(queue,
                ignored -> { throw new ProductRunWorker.ProductRunFailure("A2A_SCHEMA_INVALID"); },
                (run, code) -> failures.add(run + ":" + code))) {
            assertTrue(worker.drainOnce());
        }
        assertEquals(List.of(queue.task.runId() + ":A2A_SCHEMA_INVALID"), failures);
        assertEquals(List.of("reconcile", "recover", "claim", "running", "fail"), queue.events);
    }

    private static ProductRunWorker worker(
            FakeQueue queue, ProductRunWorker.RunGateway gateway,
            ProductRunWorker.FailureSink failures) {
        return new ProductRunWorker(queue, gateway, failures, "worker-1",
                Clock.fixed(NOW, ZoneOffset.UTC), Executors.newSingleThreadScheduledExecutor());
    }

    private static LeasedTask task(int attempt, int maximum) {
        return new LeasedTask(UUID.randomUUID(), UUID.randomUUID(), ProductRunWorker.TASK_TYPE,
                attempt, maximum, "product-run", "{\"schemaVersion\":\"1.0.0\"}",
                "worker-1", NOW.plusSeconds(60));
    }

    private static final class FakeQueue implements ProductRunWorker.TaskQueue {
        private final LeasedTask task;
        private final List<String> events = new ArrayList<>();
        private Instant availableAt;

        private FakeQueue(LeasedTask task) { this.task = task; }
        public void reconcileOrphans() { events.add("reconcile"); }
        public void recoverExpired(Instant now) { events.add("recover"); }
        public Optional<LeasedTask> claim(String workerId, Instant now, java.time.Duration lease) {
            events.add("claim");
            return Optional.of(task);
        }
        public void markRunning(UUID taskId, String workerId) { events.add("running"); }
        public void complete(UUID taskId, String workerId) { events.add("complete"); }
        public void retry(UUID taskId, String workerId, Instant value) {
            events.add("retry");
            availableAt = value;
        }
        public void fail(UUID taskId, String workerId) { events.add("fail"); }
    }
}
