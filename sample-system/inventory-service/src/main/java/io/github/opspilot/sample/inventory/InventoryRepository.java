package io.github.opspilot.sample.inventory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
class InventoryRepository {
    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Inventory> find(String sku) {
        return jdbc.sql("""
                SELECT sku, available_quantity, reserved_quantity, updated_at, version
                FROM sample.inventory WHERE sku = :sku
                """).param("sku", sku).query((rs, row) -> new Inventory(
                rs.getString("sku"), rs.getInt("available_quantity"), rs.getInt("reserved_quantity"),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"))).optional();
    }

    boolean reserve(String sku, int quantity, long expectedVersion) {
        return jdbc.sql("""
                UPDATE sample.inventory
                SET available_quantity = available_quantity - :quantity,
                    reserved_quantity = reserved_quantity + :quantity,
                    version = version + 1,
                    updated_at = now()
                WHERE sku = :sku AND version = :version AND available_quantity >= :quantity
                """).param("sku", sku).param("quantity", quantity).param("version", expectedVersion).update() == 1;
    }

    record Inventory(String sku, int availableQuantity, int reservedQuantity, Instant updatedAt, long version) { }
}
