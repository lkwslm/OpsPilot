package io.github.opspilot.sample.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Repository
class OrderRepository {
    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Order create(String sku, int quantity) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                INSERT INTO sample.orders(order_id, sku, quantity, status, created_at, updated_at, version)
                VALUES (:id, :sku, :quantity, 'RESERVED', :now, :now, 0)
                """).param("id", id).param("sku", sku).param("quantity", quantity)
                .param("now", Timestamp.from(now)).update();
        return new Order(id, sku, quantity, "RESERVED", now, now, 0);
    }

    Optional<Order> find(UUID id) {
        return jdbc.sql("""
                SELECT order_id, sku, quantity, status, created_at, updated_at, version
                FROM sample.orders WHERE order_id = :id
                """).param("id", id).query((rs, row) -> new Order(
                rs.getObject("order_id", UUID.class), rs.getString("sku"), rs.getInt("quantity"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"))).optional();
    }

    record Order(UUID orderId, String sku, int quantity, String status,
                 Instant createdAt, Instant updatedAt, long version) { }
}
