package io.github.opspilot.sample.inventory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class InventoryRepositoryIntegrationTest {
    @Test
    void optimisticConcurrentReservationAllowsOnlyOneWriterAndNeverGoesNegative() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("CREATE SCHEMA sample").update();
            jdbc.sql("""
                    CREATE TABLE sample.inventory(
                      sku text PRIMARY KEY, available_quantity int CHECK(available_quantity >= 0),
                      reserved_quantity int CHECK(reserved_quantity >= 0), updated_at timestamptz, version bigint)
                    """).update();
            jdbc.sql("INSERT INTO sample.inventory VALUES ('SKU-001',1,0,now(),0)").update();
            InventoryRepository repository = new InventoryRepository(jdbc);
            long version = repository.find("SKU-001").orElseThrow().version();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(2)) {
                for (int index = 0; index < 2; index++) executor.submit(() -> {
                    start.await();
                    if (repository.reserve("SKU-001", 1, version)) successes.incrementAndGet();
                    return null;
                });
                start.countDown();
            }
            assertEquals(1, successes.get());
            assertEquals(0, repository.find("SKU-001").orElseThrow().availableQuantity());
        }
    }
}
