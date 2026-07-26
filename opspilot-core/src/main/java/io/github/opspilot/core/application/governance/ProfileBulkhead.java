package io.github.opspilot.core.application.governance;

import io.github.opspilot.core.application.governance.DeadlineHierarchy.DeadlineExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Independent per-profile bulkheads whose queue and execution share one request deadline. */
public final class ProfileBulkhead {
    @FunctionalInterface
    public interface CancellationSignal {
        boolean cancelled();
    }

    public interface CancellableInvocation<T> {
        T execute(Instant requestDeadline) throws Exception;

        void cancel();
    }

    public record InvocationResult<T>(T value, Duration waitDuration, Duration executionDuration) {
    }

    private final Map<String, Semaphore> semaphores;
    private final Clock clock;
    private final Duration pollInterval;
    private final ExecutorService executor;

    public ProfileBulkhead(
            Map<String, Integer> profileCapacities,
            Clock clock,
            Duration pollInterval,
            ExecutorService executor) {
        Objects.requireNonNull(profileCapacities, "profileCapacities");
        if (profileCapacities.isEmpty()) {
            throw new IllegalArgumentException("at least one profile capacity is required");
        }
        LinkedHashMap<String, Semaphore> values = new LinkedHashMap<>();
        profileCapacities.forEach((profileId, capacity) -> {
            if (profileId == null || profileId.isBlank() || capacity == null || capacity <= 0) {
                throw new IllegalArgumentException("profile capacity must have a non-blank id and positive value");
            }
            values.put(profileId, new Semaphore(capacity, true));
        });
        this.semaphores = Map.copyOf(values);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public <T> InvocationResult<T> execute(
            String profileId,
            Instant requestDeadline,
            CancellationSignal cancellation,
            CancellableInvocation<T> invocation) {
        Semaphore semaphore = semaphores.get(profileId);
        if (semaphore == null) {
            throw new IllegalArgumentException("UNKNOWN_PROVIDER_PROFILE:" + profileId);
        }
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(invocation, "invocation");
        Instant waitStarted = clock.instant();
        acquire(semaphore, requestDeadline, cancellation, invocation);
        Instant acquiredAt = clock.instant();
        Future<T> future = null;
        try {
            requireActive(requestDeadline, cancellation, invocation, null);
            future = executor.submit(() -> invocation.execute(requestDeadline));
            T value = await(future, requestDeadline, cancellation, invocation);
            Instant completedAt = clock.instant();
            if (!completedAt.isBefore(requestDeadline)) {
                cancel(invocation, future);
                throw new DeadlineExceededException("LATE_PROVIDER_RESPONSE_DISCARDED");
            }
            return new InvocationResult<>(value, Duration.between(waitStarted, acquiredAt),
                    Duration.between(acquiredAt, completedAt));
        } finally {
            semaphore.release();
        }
    }

    public int availablePermits(String profileId) {
        Semaphore semaphore = semaphores.get(profileId);
        if (semaphore == null) {
            throw new IllegalArgumentException("UNKNOWN_PROVIDER_PROFILE:" + profileId);
        }
        return semaphore.availablePermits();
    }

    private void acquire(
            Semaphore semaphore,
            Instant requestDeadline,
            CancellationSignal cancellation,
            CancellableInvocation<?> invocation) {
        while (true) {
            requireActive(requestDeadline, cancellation, invocation, null);
            Duration remaining = Duration.between(clock.instant(), requestDeadline);
            long waitNanos = Math.min(remaining.toNanos(), pollInterval.toNanos());
            try {
                if (semaphore.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                invocation.cancel();
                throw new CancellationException("PROVIDER_WAIT_CANCELLED");
            }
        }
    }

    private <T> T await(
            Future<T> future,
            Instant requestDeadline,
            CancellationSignal cancellation,
            CancellableInvocation<T> invocation) {
        while (true) {
            requireActive(requestDeadline, cancellation, invocation, future);
            Duration remaining = Duration.between(clock.instant(), requestDeadline);
            long waitNanos = Math.min(remaining.toNanos(), pollInterval.toNanos());
            try {
                return future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // Re-check cancellation and the same parent deadline.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel(invocation, future);
                throw new CancellationException("PROVIDER_EXECUTION_CANCELLED");
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new InvocationExecutionException(cause);
            }
        }
    }

    private void requireActive(
            Instant requestDeadline,
            CancellationSignal cancellation,
            CancellableInvocation<?> invocation,
            Future<?> future) {
        if (cancellation.cancelled()) {
            cancel(invocation, future);
            throw new CancellationException("PROVIDER_INVOCATION_CANCELLED");
        }
        if (!clock.instant().isBefore(requestDeadline)) {
            cancel(invocation, future);
            throw new DeadlineExceededException("PROVIDER_REQUEST_DEADLINE_EXCEEDED");
        }
    }

    private static void cancel(CancellableInvocation<?> invocation, Future<?> future) {
        invocation.cancel();
        if (future != null) {
            future.cancel(true);
        }
    }

    public static final class InvocationExecutionException extends RuntimeException {
        InvocationExecutionException(Throwable cause) {
            super("PROVIDER_INVOCATION_FAILED", cause);
        }
    }
}
