package io.github.opspilot.server;

import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository;
import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository.LeasedTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Leases product Run tasks and hands them to the existing Supervisor/A2A workflow. */
final class ProductRunWorker implements AutoCloseable {
    static final String TASK_TYPE = "PRODUCT_RUN";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private final TaskQueue tasks;
    private final RunGateway gateway;
    private final FailureSink failures;
    private final String workerId;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    ProductRunWorker(
            DurableTaskRepository tasks, RunGateway gateway, FailureSink failures,
            String workerId) {
        this(new DurableQueue(tasks), gateway, failures, workerId, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("product-run-worker").daemon(true).factory()));
    }

    ProductRunWorker(
            TaskQueue tasks, RunGateway gateway, FailureSink failures, String workerId,
            Clock clock, ScheduledExecutorService scheduler) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.failures = Objects.requireNonNull(failures, "failures");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId is required");
        this.workerId = workerId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::safeDrainOnce, 0, 500, TimeUnit.MILLISECONDS);
    }

    boolean drainOnce() {
        Instant now = clock.instant();
        tasks.reconcileOrphans();
        tasks.recoverExpired(now);
        Optional<LeasedTask> candidate = tasks.claim(workerId, now, LEASE_DURATION);
        if (candidate.isEmpty()) return false;
        LeasedTask task = candidate.get();
        tasks.markRunning(task.taskId(), workerId);
        try {
            gateway.execute(task);
            tasks.complete(task.taskId(), workerId);
        } catch (Exception failure) {
            String code = failure instanceof ProductRunFailure productFailure
                    ? productFailure.code() : "PRODUCT_RUN_EXECUTION_FAILED";
            if (task.attempt() < task.maxAttempts()) {
                tasks.retry(task.taskId(), workerId, clock.instant().plus(RETRY_DELAY));
            } else {
                failures.record(task.runId(), code);
                tasks.fail(task.taskId(), workerId);
            }
        }
        return true;
    }

    private void safeDrainOnce() {
        try {
            drainOnce();
        } catch (RuntimeException failure) {
            System.err.printf("PRODUCT_RUN_WORKER_FAILURE type=%s%n", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        scheduler.close();
    }

    interface TaskQueue {
        void reconcileOrphans();
        void recoverExpired(Instant now);
        Optional<LeasedTask> claim(String workerId, Instant now, Duration leaseDuration);
        void markRunning(java.util.UUID taskId, String workerId);
        void complete(java.util.UUID taskId, String workerId);
        void retry(java.util.UUID taskId, String workerId, Instant availableAt);
        void fail(java.util.UUID taskId, String workerId);
    }

    @FunctionalInterface
    interface RunGateway {
        void execute(LeasedTask task) throws Exception;
    }

    @FunctionalInterface
    interface FailureSink {
        void record(java.util.UUID runId, String errorCode);
    }

    static final class ProductRunFailure extends Exception {
        private final String code;

        ProductRunFailure(String code) {
            super(code, null, false, false);
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) {
                throw new IllegalArgumentException("stable error code is required");
            }
            this.code = code;
        }

        String code() { return code; }
    }

    private record DurableQueue(DurableTaskRepository delegate) implements TaskQueue {
        private DurableQueue { Objects.requireNonNull(delegate, "delegate"); }
        public void reconcileOrphans() { delegate.reconcileMissingProductRunTasks(); }
        public void recoverExpired(Instant now) { delegate.markExpiredForRecovery(now, TASK_TYPE); }
        public Optional<LeasedTask> claim(String workerId, Instant now, Duration leaseDuration) {
            return delegate.claim(workerId, now, leaseDuration, TASK_TYPE);
        }
        public void markRunning(java.util.UUID taskId, String workerId) {
            delegate.markRunning(taskId, workerId);
        }
        public void complete(java.util.UUID taskId, String workerId) { delegate.complete(taskId, workerId); }
        public void retry(java.util.UUID taskId, String workerId, Instant availableAt) {
            delegate.retry(taskId, workerId, availableAt);
        }
        public void fail(java.util.UUID taskId, String workerId) { delegate.fail(taskId, workerId); }
    }
}
