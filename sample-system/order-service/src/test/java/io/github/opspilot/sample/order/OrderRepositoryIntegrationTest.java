package io.github.opspilot.sample.order;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

final class OrderRepositoryIntegrationTest {
    @Test
    void postgresCrudAndRollbackUseRealTransaction() {
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
                    CREATE TABLE sample.orders(order_id uuid PRIMARY KEY, sku text, quantity int, status text,
                      created_at timestamptz, updated_at timestamptz, version bigint)
                    """).update();
            OrderRepository repository = new OrderRepository(jdbc);
            var created = repository.create("SKU-001", 2);
            assertEquals(created, repository.find(created.orderId()).orElseThrow());

            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> {
                repository.create("SKU-002", 1);
                status.setRollbackOnly();
            });
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM sample.orders").query(Long.class).single());
        }
    }
}
