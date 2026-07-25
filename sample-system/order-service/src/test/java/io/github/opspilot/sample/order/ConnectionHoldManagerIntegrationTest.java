package io.github.opspilot.sample.order;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConnectionHoldManagerIntegrationTest {
    @Test
    void exactlyFourConnectionsCanBeHeldAndResetReleasesThePool() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            try (HikariDataSource dataSource = new HikariDataSource(config);
                 ConnectionHoldManager manager = new ConnectionHoldManager(dataSource)) {
                for (int slot = 1; slot <= 4; slot++) manager.hold(slot);
                await(() -> manager.activeHolds() == 4, Duration.ofSeconds(5));
                assertEquals(4, dataSource.getHikariPoolMXBean().getActiveConnections());
                manager.reset();
                await(() -> dataSource.getHikariPoolMXBean().getActiveConnections() == 0, Duration.ofSeconds(5));
                assertEquals(0, manager.activeHolds());
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        if (!condition.getAsBoolean()) throw new AssertionError("condition not reached before timeout");
    }
}
