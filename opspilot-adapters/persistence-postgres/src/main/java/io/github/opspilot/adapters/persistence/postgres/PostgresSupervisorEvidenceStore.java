package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.application.evidence.AnalysisSealService.AttemptReadiness;
import io.github.opspilot.core.application.evidence.AnalysisSealService.SealConflict;
import io.github.opspilot.core.application.evidence.AnalysisSealService.SealReadiness;
import io.github.opspilot.core.application.evidence.AnalysisSealService.SealRepository;
import io.github.opspilot.core.application.evidence.AnalysisSealService.SealedRun;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.EvidenceWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.HypothesisWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.ReceptionUnitOfWork;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RelationWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RemoteArtifact;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.VerificationWrite;
import io.github.opspilot.core.application.incident.RcaReportService.EvidenceView;
import io.github.opspilot.core.application.incident.RcaReportService.HypothesisView;
import io.github.opspilot.core.application.incident.RcaReportService.RelationView;
import io.github.opspilot.core.application.incident.RcaReportService.SealedAnalysis;
import io.github.opspilot.core.application.incident.RcaReportService.SealedAnalysisReader;
import io.github.opspilot.core.application.incident.RcaReportService.VerificationView;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.DelegationRecord;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.DelegationStore;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL transactions for WP07 delegation, artifact acceptance, sealing and report reads. */
public final class PostgresSupervisorEvidenceStore
        implements DelegationStore, ReceptionUnitOfWork, SealRepository, SealedAnalysisReader {
    private static final String TERMINAL_ATTEMPTS =
            "'COMPLETED','FAILED','CANCELLED','REJECTED','SKIPPED'";
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    public PostgresSupervisorEvidenceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void commitBeforeNetwork(DelegationRecord record) {
        transaction(connection -> {
            int ordinal = nextOrdinal(connection, record.runId());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.incident_step
                        (step_id, run_id, step_type, status, ordinal, version)
                    VALUES (?, ?, ?, 'DISPATCHING', ?, 0)
                    ON CONFLICT (step_id) DO UPDATE SET status = 'DISPATCHING', version = incident_step.version + 1
                    WHERE incident_step.run_id = EXCLUDED.run_id
                    """)) {
                statement.setObject(1, record.stepId().value());
                statement.setObject(2, record.runId().value());
                statement.setString(3, record.targetSkill());
                statement.setInt(4, ordinal);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("DELEGATION_STEP_CONFLICT");
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.step_attempt
                        (attempt_id, step_id, attempt_number, status, idempotency_key, request_hash,
                         message_id, remote_agent_id, session_id, target_skill, input_evidence_ids,
                         input_artifact_ids, remaining_budget_json, parent_deadline, capability_snapshot_json)
                    VALUES (?, ?, ?, 'DISPATCHING', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
                    """)) {
                UUID attemptId = UUID.randomUUID();
                statement.setObject(1, attemptId);
                statement.setObject(2, record.stepId().value());
                statement.setInt(3, record.attempt());
                statement.setString(4, record.messageId());
                statement.setString(5, requestHash(record));
                statement.setString(6, record.messageId());
                statement.setString(7, record.targetAgent());
                statement.setString(8, "attempt:" + attemptId);
                statement.setString(9, record.targetSkill());
                statement.setArray(10, connection.createArrayOf("uuid",
                        record.evidenceIds().stream().map(id -> id.value()).toArray()));
                statement.setArray(11, connection.createArrayOf("uuid",
                        record.artifactIds().stream().map(id -> id.value()).toArray()));
                statement.setString(12, budgetJson(record));
                statement.setTimestamp(13, Timestamp.from(record.deadline()));
                statement.setString(14, snapshotJson(record.capabilitySnapshot()));
                statement.executeUpdate();
            }
            return null;
        }, "DELEGATION_CHECKPOINT_FAILED");
    }

    @Override
    public void markSkipped(RunId runId, StepId stepId, String reasonCode) {
        transaction(connection -> {
            int ordinal = nextOrdinal(connection, runId);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.incident_step
                        (step_id, run_id, step_type, status, ordinal, version)
                    VALUES (?, ?, ?, 'SKIPPED', ?, 0)
                    ON CONFLICT (step_id) DO UPDATE SET status = 'SKIPPED', version = incident_step.version + 1
                    WHERE incident_step.run_id = EXCLUDED.run_id
                    """)) {
                statement.setObject(1, stepId.value());
                statement.setObject(2, runId.value());
                statement.setString(3, reasonCode);
                statement.setInt(4, ordinal);
                statement.executeUpdate();
            }
            return null;
        }, "STEP_SKIP_FAILED");
    }

    @Override
    public void recordMissingEvidence(RunId runId, String reasonCode) {
        transaction(connection -> {
            insertMissing(connection, runId, reasonCode);
            return null;
        }, "MISSING_EVIDENCE_WRITE_FAILED");
    }

    @Override
    public void commit(RemoteArtifact artifact) {
        transaction(connection -> {
            if (receptionExists(connection, artifact)) {
                return null;
            }
            assertTaskOwnership(connection, artifact);
            insertArtifact(connection, artifact);
            for (EvidenceWrite evidence : artifact.mutation().evidence()) {
                insertEvidence(connection, artifact, evidence);
            }
            for (HypothesisWrite hypothesis : artifact.mutation().hypotheses()) {
                insertHypothesis(connection, artifact, hypothesis);
            }
            for (RelationWrite relation : artifact.mutation().relations()) {
                insertRelation(connection, relation);
            }
            for (VerificationWrite verification : artifact.mutation().verifications()) {
                insertVerification(connection, verification);
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.artifact_reception (artifact_id, run_id, task_id, status)
                    VALUES (?, ?, ?, 'ACCEPTED')
                    """)) {
                statement.setObject(1, artifact.artifactId().value());
                statement.setObject(2, artifact.runId().value());
                statement.setObject(3, artifact.taskId());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.outbox_event
                        (event_id, run_id, fact_type, state_version, payload_json, occurred_at)
                    SELECT ?, ?, 'ARTIFACT_ACCEPTED', run_version,
                           jsonb_build_object('schemaVersion','1.0.0','artifactId',?::text), now()
                    FROM opspilot.incident_run WHERE run_id = ?
                    """)) {
                statement.setObject(1, artifact.mutation().outboxEventId());
                statement.setObject(2, artifact.runId().value());
                statement.setObject(3, artifact.artifactId().value());
                statement.setObject(4, artifact.runId().value());
                statement.executeUpdate();
            }
            return null;
        }, "ARTIFACT_RECEPTION_FAILED");
    }

    @Override
    public SealReadiness readiness(RunId runId) {
        String sql = """
                SELECT a.attempt_id::text,
                       a.status IN (%s) AS terminal,
                       m.reason_code
                FROM opspilot.step_attempt a
                JOIN opspilot.incident_step s ON s.step_id = a.step_id
                LEFT JOIN opspilot.missing_evidence m ON m.source_attempt_id = a.attempt_id
                WHERE s.run_id = ? ORDER BY s.ordinal, a.attempt_number
                """.formatted(TERMINAL_ATTEMPTS);
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId.value());
            List<AttemptReadiness> attempts = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    attempts.add(new AttemptReadiness(
                            result.getString(1), result.getBoolean(2), result.getString(3)));
                }
            }
            return new SealReadiness(attempts, 0);
        } catch (SQLException exception) {
            throw new IllegalStateException("SEAL_READINESS_FAILED", exception);
        }
    }

    @Override
    public SealedRun sealAtomically(
            RunId runId, long expectedVersion, java.time.Instant sealedAt, List<String> missingEvidence) {
        return transaction(connection -> {
            for (String reason : missingEvidence) {
                insertMissing(connection, runId, reason);
            }
            String sql = """
                    UPDATE opspilot.incident_run r
                    SET status = 'GENERATING_REPORT', run_version = run_version + 1,
                        analysis_sealed_at = ?, updated_at = now()
                    WHERE r.run_id = ? AND r.run_version = ? AND r.analysis_sealed_at IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM opspilot.incident_step s
                          JOIN opspilot.step_attempt a ON a.step_id = s.step_id
                          LEFT JOIN opspilot.missing_evidence m ON m.source_attempt_id = a.attempt_id
                          WHERE s.run_id = r.run_id
                            AND a.status NOT IN (%s)
                            AND m.reason_code IS NULL)
                    RETURNING run_version
                    """.formatted(TERMINAL_ATTEMPTS);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(sealedAt));
                statement.setObject(2, runId.value());
                statement.setLong(3, expectedVersion);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SealConflict("ANALYSIS_SEAL_CONFLICT");
                    }
                    return new SealedRun(runId, result.getLong(1), sealedAt, missingEvidence);
                }
            }
        }, "ANALYSIS_SEAL_FAILED");
    }

    @Override
    public SealedAnalysis inReadOnlyTransaction(RunId runId, long runVersion) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                SealedRunSnapshot sealed = readSealedVersion(connection, runId, runVersion);
                SealedAnalysis result = new SealedAnalysis(
                        sealed.incidentId(), runId, runVersion, sealed.status(), sealed.sealedAt(),
                        sealed.startedAt(), sealed.endedAt(),
                        readEvidence(connection, runId), readHypotheses(connection, runId),
                        readRelations(connection, runId), readVerifications(connection, runId),
                        readMissing(connection, runId));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("SEALED_ANALYSIS_READ_FAILED", exception);
        }
    }

    private static int nextOrdinal(Connection connection, RunId runId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COALESCE(max(ordinal), -1) + 1 FROM opspilot.incident_step WHERE run_id = ?")) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private String budgetJson(DelegationRecord record) {
        try {
            return json.writeValueAsString(java.util.Map.of(
                    "schemaVersion", "1.0.0", "rounds", record.remainingBudget().rounds(),
                    "agentCalls", record.remainingBudget().agentCalls(), "a2aCalls", record.remainingBudget().a2aCalls(),
                    "tokens", record.remainingBudget().tokens(), "costMicros", record.remainingBudget().costMicros()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("BUDGET_SERIALIZATION_FAILED", exception);
        }
    }

    private String snapshotJson(String snapshot) {
        try {
            return json.writeValueAsString(java.util.Map.of("schemaVersion", "1.0.0", "digest", snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("CAPABILITY_SNAPSHOT_SERIALIZATION_FAILED", exception);
        }
    }

    private static String requestHash(DelegationRecord record) {
        String canonical = record.runId().wire() + ":" + record.stepId().wire() + ":" + record.attempt()
                + ":" + record.targetAgent() + ":" + record.targetSkill();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void insertMissing(Connection connection, RunId runId, String reason) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.missing_evidence (run_id, reason_code)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """)) {
            statement.setObject(1, runId.value());
            statement.setString(2, reason);
            statement.executeUpdate();
        }
    }

    private static boolean receptionExists(Connection connection, RemoteArtifact artifact) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM opspilot.artifact_reception
                WHERE artifact_id = ? AND run_id = ? AND task_id = ?
                """)) {
            statement.setObject(1, artifact.artifactId().value());
            statement.setObject(2, artifact.runId().value());
            statement.setObject(3, artifact.taskId());
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static void assertTaskOwnership(Connection connection, RemoteArtifact artifact) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM opspilot.task WHERE task_id = ? AND run_id = ?")) {
            statement.setObject(1, artifact.taskId());
            statement.setObject(2, artifact.runId().value());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("ARTIFACT_TASK_WRONG_RUN");
            }
        }
    }

    private static void insertArtifact(Connection connection, RemoteArtifact artifact) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.artifact
                    (artifact_id, run_id, uri, sha256, media_type, access_level, object_key,
                     size_bytes, metadata_json, task_id)
                VALUES (?, ?, ?, ?, ?, 'RUN_PRIVATE', ?, ?, ?::jsonb, ?)
                """)) {
            statement.setObject(1, artifact.artifactId().value());
            statement.setObject(2, artifact.runId().value());
            statement.setString(3, "a2a://" + artifact.taskId() + "/" + artifact.artifactId().wire());
            statement.setString(4, artifact.sha256());
            statement.setString(5, artifact.mediaType());
            statement.setString(6, "a2a/" + artifact.runId().wire() + "/" + artifact.artifactId().wire());
            statement.setLong(7, artifact.payload().length);
            statement.setString(8, "{\"schemaVersion\":\"" + artifact.schemaVersion() + "\"}");
            statement.setObject(9, artifact.taskId());
            statement.executeUpdate();
        }
    }

    private static void insertEvidence(Connection connection, RemoteArtifact artifact, EvidenceWrite evidence)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.evidence
                    (evidence_id, run_id, evidence_code, summary, artifact_id, attributes,
                     source_type, source_id, observed_at)
                VALUES (?, ?, ?, ?, ?, '{"schemaVersion":"1.0.0"}'::jsonb,
                        ?, ?, COALESCE(?, now()))
                """)) {
            statement.setObject(1, evidence.evidenceId().value());
            statement.setObject(2, artifact.runId().value());
            statement.setString(3, evidence.evidenceCode());
            statement.setString(4, evidence.summary());
            statement.setObject(5, artifact.artifactId().value());
            statement.setString(6, evidence.sourceType());
            statement.setString(7, artifact.sourceId());
            statement.setTimestamp(8, evidence.observedAt() == null
                    ? null : Timestamp.from(evidence.observedAt()));
            statement.executeUpdate();
        }
    }

    private static void insertHypothesis(
            Connection connection, RemoteArtifact artifact, HypothesisWrite hypothesis) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.hypothesis
                    (hypothesis_id, run_id, statement, status, confidence)
                VALUES (?, ?, ?, 'PROPOSED', 0)
                """)) {
            statement.setObject(1, hypothesis.hypothesisId().value());
            statement.setObject(2, artifact.runId().value());
            statement.setString(3, hypothesis.statement());
            statement.executeUpdate();
        }
    }

    private static void insertRelation(Connection connection, RelationWrite relation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.hypothesis_evidence
                    (hypothesis_id, evidence_id, relation, rationale_summary)
                VALUES (?, ?, ?, 'validated remote artifact')
                """)) {
            statement.setObject(1, relation.hypothesisId().value());
            statement.setObject(2, relation.evidenceId().value());
            statement.setString(3, relation.relation());
            statement.executeUpdate();
        }
    }

    private static void insertVerification(Connection connection, VerificationWrite verification) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO opspilot.hypothesis_verification
                    (verification_id, hypothesis_id, evidence_id, result, summary)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, verification.verificationId());
            statement.setObject(2, verification.hypothesisId().value());
            statement.setObject(3, verification.evidenceId().value());
            statement.setString(4, verification.result());
            statement.setString(5, verification.summary());
            statement.executeUpdate();
        }
    }

    private static SealedRunSnapshot readSealedVersion(Connection connection, RunId runId, long version)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT incident_id, status, analysis_sealed_at, started_at,
                       COALESCE(ended_at, analysis_sealed_at)
                FROM opspilot.incident_run
                WHERE run_id = ? AND run_version = ? AND status = 'GENERATING_REPORT'
                  AND analysis_sealed_at IS NOT NULL
                """)) {
            statement.setObject(1, runId.value());
            statement.setLong(2, version);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("SEALED_RUN_VERSION_NOT_FOUND");
                return new SealedRunSnapshot(
                        new IncidentId(result.getObject(1, UUID.class)), result.getString(2),
                        result.getTimestamp(3).toInstant(), result.getTimestamp(4).toInstant(),
                        result.getTimestamp(5).toInstant());
            }
        }
    }

    private static List<EvidenceView> readEvidence(Connection connection, RunId runId) throws SQLException {
        List<EvidenceView> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT e.evidence_id, e.evidence_code, e.summary, e.artifact_id,
                       a.sha256, a.access_level, e.observed_at
                FROM opspilot.evidence e
                LEFT JOIN opspilot.artifact a ON a.artifact_id = e.artifact_id
                WHERE e.run_id = ? ORDER BY e.evidence_id
                """)) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(new EvidenceView(
                        new EvidenceId(result.getObject(1, UUID.class)), result.getString(2), result.getString(3),
                        result.getObject(4) == null ? null : new ArtifactId(result.getObject(4, UUID.class)),
                        result.getString(5), !"GROUND_TRUTH".equals(result.getString(6))
                                && !"EVALUATION_ONLY".equals(result.getString(6)),
                        result.getTimestamp(7) == null ? null : result.getTimestamp(7).toInstant()));
            }
        }
        return values;
    }

    private static List<HypothesisView> readHypotheses(Connection connection, RunId runId) throws SQLException {
        List<HypothesisView> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT hypothesis_id, statement, status, confidence FROM opspilot.hypothesis
                WHERE run_id = ? ORDER BY hypothesis_id
                """)) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(new HypothesisView(
                        new HypothesisId(result.getObject(1, UUID.class)), result.getString(2), result.getString(3),
                        result.getDouble(4)));
            }
        }
        return values;
    }

    private static List<RelationView> readRelations(Connection connection, RunId runId) throws SQLException {
        List<RelationView> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT r.hypothesis_id, r.evidence_id, r.relation
                FROM opspilot.hypothesis_evidence r
                JOIN opspilot.hypothesis h ON h.hypothesis_id = r.hypothesis_id
                WHERE h.run_id = ? ORDER BY r.hypothesis_id, r.evidence_id, r.relation
                """)) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(new RelationView(
                        new HypothesisId(result.getObject(1, UUID.class)),
                        new EvidenceId(result.getObject(2, UUID.class)), result.getString(3)));
            }
        }
        return values;
    }

    private static List<VerificationView> readVerifications(Connection connection, RunId runId) throws SQLException {
        List<VerificationView> values = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT v.hypothesis_id, v.evidence_id, v.result, v.summary
                FROM opspilot.hypothesis_verification v
                JOIN opspilot.hypothesis h ON h.hypothesis_id = v.hypothesis_id
                WHERE h.run_id = ? ORDER BY v.verification_id
                """)) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(new VerificationView(
                        new HypothesisId(result.getObject(1, UUID.class)),
                        result.getObject(2) == null ? null : new EvidenceId(result.getObject(2, UUID.class)),
                        result.getString(3), result.getString(4)));
            }
        }
        return values;
    }

    private static List<String> readMissing(Connection connection, RunId runId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT reason_code FROM opspilot.missing_evidence WHERE run_id = ? ORDER BY reason_code")) {
            statement.setObject(1, runId.value());
            try (var result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return values;
    }

    private <T> T transaction(SqlWork<T> work, String code) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                if (failure instanceof SealConflict conflict) throw conflict;
                throw new IllegalStateException(code, failure);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(code, exception);
        }
    }

    private record SealedRunSnapshot(
            IncidentId incidentId, String status, java.time.Instant sealedAt,
            java.time.Instant startedAt, java.time.Instant endedAt) { }

    @FunctionalInterface
    private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
}
