package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.application.incident.RcaReportService.RcaMetadata;
import io.github.opspilot.core.application.incident.RcaReportService.RcaMetadataWriter;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** Atomically persists the two rendered artifacts and their single-source RCA metadata. */
public final class PostgresRcaMetadataRepository implements RcaMetadataWriter {
    private final DataSource dataSource;

    public PostgresRcaMetadataRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void save(RcaMetadata metadata, String jsonArtifact, String markdownArtifact) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (alreadySaved(connection, metadata)) {
                    connection.commit();
                    return;
                }
                UUID jsonId = UUID.randomUUID();
                UUID markdownId = UUID.randomUUID();
                insertArtifact(connection, metadata, jsonId, "application/vnd.opspilot.rca+json",
                        metadata.objectDigest(), jsonArtifact.getBytes(StandardCharsets.UTF_8).length, "rca.json");
                insertArtifact(connection, metadata, markdownId, "text/markdown",
                        metadata.markdownDigest(), markdownArtifact.getBytes(StandardCharsets.UTF_8).length, "rca.md");
                try (var statement = connection.prepareStatement("""
                        INSERT INTO opspilot.rca_report
                            (report_id, run_id, report_artifact_id, markdown_artifact_id,
                             run_version, analysis_sealed_at, schema_version, input_digest,
                             object_digest, report_json, report_markdown)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """)) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, metadata.runId().value());
                    statement.setObject(3, jsonId);
                    statement.setObject(4, markdownId);
                    statement.setLong(5, metadata.runVersion());
                    statement.setTimestamp(6, Timestamp.from(metadata.analysisSealedAt()));
                    statement.setString(7, metadata.schemaVersion());
                    statement.setString(8, metadata.inputDigest());
                    statement.setString(9, metadata.objectDigest());
                    statement.setString(10, jsonArtifact);
                    statement.setString(11, markdownArtifact);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("RCA_METADATA_SAVE_FAILED", exception);
        }
    }

    private static boolean alreadySaved(Connection connection, RcaMetadata metadata) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT input_digest, object_digest FROM opspilot.rca_report
                WHERE run_id = ? AND run_version = ?
                """)) {
            statement.setObject(1, metadata.runId().value());
            statement.setLong(2, metadata.runVersion());
            try (var result = statement.executeQuery()) {
                if (!result.next()) return false;
                if (!metadata.inputDigest().equals(result.getString(1))
                        || !metadata.objectDigest().equals(result.getString(2))) {
                    throw new IllegalStateException("RCA_METADATA_INPUT_DIGEST_CONFLICT");
                }
                return true;
            }
        }
    }

    private static void insertArtifact(Connection connection, RcaMetadata metadata, UUID id,
                                       String mediaType, String sha256, int size, String suffix)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.artifact
                    (artifact_id, run_id, uri, sha256, media_type, access_level,
                     storage_provider, object_key, size_bytes, retention_class, metadata_json)
                VALUES (?, ?, ?, ?, ?, 'RUN_PRIVATE', 'postgres', ?, ?, 'RCA', ?::jsonb)
                """)) {
            String key = "rca/" + metadata.runId().wire() + "/" + metadata.runVersion() + "/" + suffix;
            statement.setObject(1, id);
            statement.setObject(2, metadata.runId().value());
            statement.setString(3, "db://opspilot.rca_report/" + key);
            statement.setString(4, sha256);
            statement.setString(5, mediaType);
            statement.setString(6, key);
            statement.setInt(7, size);
            statement.setString(8, "{\"schemaVersion\":\"" + metadata.schemaVersion() + "\"}");
            statement.executeUpdate();
        }
    }
}
