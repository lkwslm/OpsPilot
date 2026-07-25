package io.github.opspilot.sample.inventory;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
class FaultState {
    private final AtomicLong delayMillis = new AtomicLong();
    private final AtomicBoolean exception = new AtomicBoolean();
    private final AtomicBoolean blocked = new AtomicBoolean();

    void configure(Long delay, Boolean fail, Boolean block) {
        if (delay != null) delayMillis.set(Math.max(0, Math.min(delay, Duration.ofSeconds(30).toMillis())));
        if (fail != null) exception.set(fail);
        if (block != null) blocked.set(block);
    }

    void beforeRequest() {
        long delay = delayMillis.get();
        if (delay > 0) sleep(delay);
        while (blocked.get()) sleep(25);
        if (exception.get()) throw new ControlledFault();
    }

    void reset() {
        delayMillis.set(0);
        exception.set(false);
        blocked.set(false);
    }

    Snapshot snapshot() {
        return new Snapshot(delayMillis.get(), exception.get(), blocked.get());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ControlledFault();
        }
    }

    record Snapshot(long delayMillis, boolean exception, boolean blocked) { }
    static final class ControlledFault extends RuntimeException { }
}
