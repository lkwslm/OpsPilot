package io.github.opspilot.adapters.persistence.postgres;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Fail-closed readiness check for the exact Flyway and pgvector versions. */
public final class PostgresReadinessCheck {
    private final DataSource dataSource;
    private final String expectedMigrationVersion;
    private final String expectedPgvectorVersion;

    public PostgresReadinessCheck(
            DataSource dataSource, String expectedMigrationVersion, String expectedPgvectorVersion) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.expectedMigrationVersion = required(expectedMigrationVersion, "expectedMigrationVersion");
        this.expectedPgvectorVersion = required(expectedPgvectorVersion, "expectedPgvectorVersion");
    }

    public Result check() {
        try (var connection = dataSource.getConnection()) {
            String migration = query(connection, """
                    SELECT version FROM flyway_schema_history
                    WHERE success AND version IS NOT NULL
                    ORDER BY installed_rank DESC LIMIT 1
                    """);
            if (!expectedMigrationVersion.equals(migration)) {
                return Result.down("FLYWAY_VERSION_MISMATCH");
            }
            String extension = query(connection,
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
            if (!expectedPgvectorVersion.equals(extension)) {
                return Result.down("PGVECTOR_VERSION_MISMATCH");
            }
            return Result.up();
        } catch (SQLException exception) {
            return Result.down("POSTGRES_READINESS_FAILED");
        }
    }

    private static String query(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record Result(boolean ready, String reason) {
        static Result up() {
            return new Result(true, "READY");
        }

        static Result down(String reason) {
            return new Result(false, reason);
        }
    }
}
