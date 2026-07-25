package io.github.opspilot.sample.order;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Profile({"fault-lab", "test"})
class ConnectionHoldManager implements AutoCloseable {
    private final DataSource dataSource;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, Hold> holds = new ConcurrentHashMap<>();

    ConnectionHoldManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void hold(int slot) {
        if (slot < 1 || slot > 4) throw new IllegalArgumentException("slot must be in [1,4]");
        holds.computeIfAbsent(slot, ignored -> {
            Hold hold = new Hold();
            executor.submit(() -> occupy(hold));
            return hold;
        });
    }

    int activeHolds() {
        return (int) holds.values().stream().filter(Hold::acquired).count();
    }

    void reset() {
        holds.values().forEach(Hold::release);
        holds.clear();
    }

    @Override
    public void close() {
        reset();
        executor.close();
    }

    private void occupy(Hold hold) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            hold.acquired.countDown();
            hold.release.await();
            connection.rollback();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException failure) {
            hold.failure = failure;
            hold.acquired.countDown();
        }
    }

    private static final class Hold {
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile SQLException failure;

        boolean acquired() {
            return acquired.getCount() == 0 && failure == null && release.getCount() > 0;
        }

        void release() {
            release.countDown();
        }
    }
}
