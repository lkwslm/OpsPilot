package io.github.opspilot.evaluation;

import io.github.opspilot.evaluation.EvaluationModels.EvaluationProfile;
import io.github.opspilot.evaluation.EvaluationReportRenderer.RenderedEvaluation;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Atomically stores both reports and an idempotent result keyed by immutable profile version. */
public final class PostgresEvaluationResultRepository {
    private final DataSource dataSource;

    public PostgresEvaluationResultRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void save(UUID runId, EvaluationProfile profile, RenderedEvaluation rendered) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (alreadySaved(connection, runId, profile, rendered.resultDigest())) {
                    connection.commit();
                    return;
                }
                UUID jsonArtifactId = UUID.randomUUID();
                UUID markdownArtifactId = UUID.randomUUID();
                insertArtifact(connection, runId, profile, jsonArtifactId,
                        "application/vnd.opspilot.evaluation+json", rendered.resultDigest(),
                        rendered.jsonReport(), "evaluation.json");
                insertArtifact(connection, runId, profile, markdownArtifactId,
                        "text/markdown", sha256(rendered.markdownReport()),
                        rendered.markdownReport(), "evaluation.md");
                try (var statement = connection.prepareStatement("""
                        INSERT INTO opspilot.evaluation_result
                            (evaluation_id, run_id, status, metrics_json, report_artifact_id,
                             profile_id, profile_version, scenario_id, profile_snapshot_sha256,
                             result_digest, json_report_artifact_id, markdown_report_artifact_id,
                             report_json, report_markdown)
                        VALUES (?, ?, 'COMPLETED', ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """)) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, runId);
                    statement.setString(3, rendered.jsonReport());
                    statement.setObject(4, jsonArtifactId);
                    statement.setString(5, profile.profileId());
                    statement.setString(6, profile.profileVersion());
                    statement.setString(7, rendered.result().scenarioId());
                    statement.setString(8, profile.snapshotSha256());
                    statement.setString(9, rendered.resultDigest());
                    statement.setObject(10, jsonArtifactId);
                    statement.setObject(11, markdownArtifactId);
                    statement.setString(12, rendered.jsonReport());
                    statement.setString(13, rendered.markdownReport());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("EVALUATION_RESULT_WRITE_FAILED", exception);
        }
    }

    private static boolean alreadySaved(
            Connection connection, UUID runId, EvaluationProfile profile, String digest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT result_digest FROM opspilot.evaluation_result
                WHERE run_id = ? AND profile_id = ? AND profile_version = ?
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, profile.profileId());
            statement.setString(3, profile.profileVersion());
            try (var result = statement.executeQuery()) {
                if (!result.next()) return false;
                if (!digest.equals(result.getString(1))) {
                    throw new IllegalStateException("EVALUATION_RESULT_CONFLICT");
                }
                return true;
            }
        }
    }

    private static void insertArtifact(
            Connection connection, UUID runId, EvaluationProfile profile, UUID artifactId,
            String mediaType, String digest, String content, String suffix) throws SQLException {
        String key = "evaluation/" + runId + "/" + profile.profileId() + "/"
                + profile.profileVersion() + "/" + suffix;
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.artifact
                    (artifact_id, run_id, uri, sha256, media_type, access_level,
                     storage_provider, object_key, size_bytes, retention_class, metadata_json)
                VALUES (?, ?, ?, ?, ?, 'EVALUATION', 'postgres', ?, ?, 'EVALUATION', ?::jsonb)
                """)) {
            statement.setObject(1, artifactId);
            statement.setObject(2, runId);
            statement.setString(3, "db://opspilot.evaluation_result/" + key);
            statement.setString(4, digest);
            statement.setString(5, mediaType);
            statement.setString(6, key);
            statement.setInt(7, content.getBytes(StandardCharsets.UTF_8).length);
            statement.setString(8, "{\"schemaVersion\":\"1.0.0\"}");
            statement.executeUpdate();
        }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
