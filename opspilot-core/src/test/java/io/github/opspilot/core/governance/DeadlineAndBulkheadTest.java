package io.github.opspilot.core.governance;

import io.github.opspilot.core.application.governance.DeadlineHierarchy;
import io.github.opspilot.core.application.governance.DeadlineHierarchy.DeadlineExceededException;
import io.github.opspilot.core.application.governance.DeadlineHierarchy.Policy;
import io.github.opspilot.core.application.governance.ProfileBulkhead;
import io.github.opspilot.core.application.governance.ProfileBulkhead.CancellableInvocation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlineAndBulkheadTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void derivesEveryChildFromTheRemainingParentDeadlineWithAControlledClock() {
        MutableClock clock = new MutableClock(NOW);
        Policy policy = new Policy(Duration.ofSeconds(10), Duration.ofSeconds(8),
                Duration.ofSeconds(3), Duration.ofSeconds(4));

        var deadlines = new DeadlineHierarchy().derive(
                clock.instant(), NOW.plusSeconds(5), policy);

        assertEquals(NOW.plusSeconds(5), deadlines.step());
        assertEquals(NOW.plusSeconds(5), deadlines.request());
        assertEquals(NOW.plusSeconds(3), deadlines.connect());
        assertEquals(NOW.plusSeconds(5), deadlines.read());
        clock.advance(Duration.ofSeconds(6));
        assertEquals("INCIDENT_DEADLINE_EXCEEDED",
                assertThrows(DeadlineExceededException.class,
                        () -> new DeadlineHierarchy().derive(clock.instant(), NOW.plusSeconds(5), policy)).getMessage());
    }

    @Test
    void semaphoreWaitIsMeasuredAndProfilesAreIsolated() throws Exception {
        try (var invocationExecutor = Executors.newFixedThreadPool(3);
             var callers = Executors.newFixedThreadPool(3)) {
            ProfileBulkhead bulkhead = new ProfileBulkhead(Map.of("slow", 1, "fast", 1),
                    Clock.systemUTC(), Duration.ofMillis(5), invocationExecutor);
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            var first = callers.submit(() -> bulkhead.execute("slow", Instant.now().plusSeconds(2),
                    () -> false, blocking("first", slowStarted, releaseSlow)));
            assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
            var queued = callers.submit(() -> bulkhead.execute("slow", Instant.now().plusSeconds(2),
                    () -> false, immediate("second")));
            var isolated = callers.submit(() -> bulkhead.execute("fast", Instant.now().plusSeconds(2),
                    () -> false, immediate("fast-result")));

            assertEquals("fast-result", isolated.get(1, TimeUnit.SECONDS).value());
            Thread.sleep(30);
            releaseSlow.countDown();

            assertEquals("first", first.get(1, TimeUnit.SECONDS).value());
            assertEquals("second", queued.get(1, TimeUnit.SECONDS).value());
            assertTrue(queued.get().waitDuration().toMillis() >= 20);
            assertEquals(1, bulkhead.availablePermits("slow"));
            assertEquals(1, bulkhead.availablePermits("fast"));
        }
    }

    @Test
    void cancellationCancelsTheInvocationAndReleasesThePermit() throws Exception {
        try (var invocationExecutor = Executors.newSingleThreadExecutor();
             var caller = Executors.newSingleThreadExecutor()) {
            ProfileBulkhead bulkhead = new ProfileBulkhead(Map.of("profile", 1),
                    Clock.systemUTC(), Duration.ofMillis(5), invocationExecutor);
            AtomicBoolean cancellation = new AtomicBoolean();
            AtomicBoolean cancelCalled = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            CancellableInvocation<String> invocation = new CancellableInvocation<>() {
                @Override
                public String execute(Instant deadline) throws InterruptedException {
                    started.countDown();
                    while (true) {
                        Thread.sleep(1000);
                    }
                }

                @Override
                public void cancel() {
                    cancelCalled.set(true);
                }
            };
            var result = caller.submit(() -> bulkhead.execute("profile", Instant.now().plusSeconds(2),
                    cancellation::get, invocation));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            cancellation.set(true);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(cancelCalled.get());
            assertEquals(1, bulkhead.availablePermits("profile"));
        }
    }

    @Test
    void lateResponseIsDiscardedAndAllFailurePathsReleasePermits() {
        MutableClock clock = new MutableClock(NOW);
        AtomicBoolean cancelCalled = new AtomicBoolean();
        try (var invocationExecutor = Executors.newSingleThreadExecutor()) {
            ProfileBulkhead bulkhead = new ProfileBulkhead(Map.of("profile", 1),
                    clock, Duration.ofSeconds(1), invocationExecutor);
            CancellableInvocation<String> late = new CancellableInvocation<>() {
                @Override
                public String execute(Instant deadline) {
                    clock.advance(Duration.ofSeconds(2));
                    return "too late";
                }

                @Override
                public void cancel() {
                    cancelCalled.set(true);
                }
            };

            DeadlineExceededException failure = assertThrows(DeadlineExceededException.class,
                    () -> bulkhead.execute("profile", NOW.plusSeconds(1), () -> false, late));

            assertEquals("LATE_PROVIDER_RESPONSE_DISCARDED", failure.getMessage());
            assertTrue(cancelCalled.get());
            assertEquals(1, bulkhead.availablePermits("profile"));
        }
    }

    @Test
    void bulkheadBoundaryCannotHoldAJdbcTransactionOpen() {
        assertTrue(Arrays.stream(ProfileBulkhead.class.getDeclaredFields())
                .noneMatch(field -> java.sql.Connection.class.isAssignableFrom(field.getType())));
        assertTrue(Arrays.stream(ProfileBulkhead.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(java.sql.Connection.class::isAssignableFrom));
    }

    private static CancellableInvocation<String> immediate(String result) {
        return new CancellableInvocation<>() {
            @Override public String execute(Instant deadline) { return result; }
            @Override public void cancel() { }
        };
    }

    private static CancellableInvocation<String> blocking(
            String result, CountDownLatch started, CountDownLatch release) {
        return new CancellableInvocation<>() {
            @Override
            public String execute(Instant deadline) throws InterruptedException {
                started.countDown();
                release.await();
                return result;
            }

            @Override public void cancel() { }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
