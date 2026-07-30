package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.AccessContext;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.AccessLevel;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.ArtifactAccessDeniedException;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.ArtifactConflictException;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.ArtifactPathException;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.IssueType;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.RetentionClass;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService.WriteRequest;
import io.github.opspilot.adapters.persistence.postgres.entity.AgentStateEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.HypothesisEntity;
import io.github.opspilot.adapters.persistence.postgres.entity.IncidentRunEntity;
import io.github.opspilot.core.application.checkpoint.CommittedEventProjector;
import io.github.opspilot.core.application.correlation.CorrelationContext;
import io.github.opspilot.core.application.evidence.ArtifactReceiver;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService;
import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.ReactLoopSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.UsageStatistics;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptConflict;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.AttemptDraft;
import io.github.opspilot.core.port.repository.A2aAttemptRepository.RemoteBinding;
import io.github.opspilot.core.port.repository.CheckpointContracts.A2aBindingWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.BindingType;
import io.github.opspilot.core.port.repository.CheckpointContracts.CallAudit;
import io.github.opspilot.core.port.repository.CheckpointContracts.CallKind;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointConflict;
import io.github.opspilot.core.port.repository.CheckpointContracts.CodeSnapshotWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.DomainEvent;
import io.github.opspilot.core.port.repository.CheckpointContracts.ReferenceBinding;
import io.github.opspilot.core.port.repository.CheckpointContracts.StepAttemptWrite;
import io.github.opspilot.core.port.repository.CheckpointContracts.StepWrite;
import io.github.opspilot.core.port.repository.UsageLedgerRepository.UsageRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.RollbackException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PostgresPhase3AdapterTest {
    private static final String IMAGE = "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";
    private static final String DATABASE = "phase3_adapter";
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static PostgreSQLContainer postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword("phase3-test-only");
        postgres.start();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(resource("/db/bootstrap/roles.sql"));
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void readinessRolesRlsAndSchemaGateFailClosed() throws Exception {
        assertTrue(new PostgresReadinessCheck(dataSource, "24", "0.8.4").check().ready());
        assertEquals("FLYWAY_VERSION_MISMATCH",
                new PostgresReadinessCheck(dataSource, "99", "0.8.4").check().reason());
        assertEquals("PGVECTOR_VERSION_MISMATCH",
                new PostgresReadinessCheck(dataSource, "24", "99").check().reason());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertFalse(queryBoolean(statement, """
                    SELECT bool_or(rolsuper OR rolcreatedb OR rolcreaterole)
                    FROM pg_roles WHERE rolname IN (
                        'opspilot_app_role','evidence_agent_role','code_agent_role',
                        'knowledge_agent_role','diagnosis_agent_role','remediation_agent_role')
                    """));
            statement.executeUpdate("""
                    INSERT INTO opspilot_a2a.task
                        (task_id,server_agent_id,message_id,request_hash,state,payload_json)
                    VALUES ('rls-evidence','evidence-collector','rls-message-1','hash','SUBMITTED',
                            '{"schemaVersion":"1.0.0"}'),
                           ('rls-code','code-analysis','rls-message-2','hash','SUBMITTED',
                            '{"schemaVersion":"1.0.0"}')
                    """);
            statement.execute("SET ROLE evidence_agent_role");
            assertEquals(1, queryInt(statement, "SELECT count(*) FROM opspilot_a2a.task"));
            assertEquals(0, statement.executeUpdate(
                    "UPDATE opspilot_a2a.task SET state='WORKING' WHERE task_id='rls-code'"));
            PSQLException ddlDenied = assertThrows(PSQLException.class,
                    () -> statement.execute("CREATE TABLE opspilot.forbidden(id int)"));
            assertEquals("42501", ddlDenied.getSQLState());
            PSQLException truthDenied = assertThrows(PSQLException.class,
                    () -> statement.executeQuery("SELECT * FROM opspilot_eval.ground_truth"));
            assertEquals("42501", truthDenied.getSQLState());
        }
    }

    @Test
    void usageLedgerPersistsFailedEstimatedAttemptsAndRemainsAppendOnly() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        UsageRecord record = new UsageRecord(
                invocationId, incidentId.toString(), "task-1", "agent-1",
                40, 20, 5, null, false, null,
                "CONSERVATIVE_ESTIMATE", "estimate-v1", "FAILED", NOW);
        PostgresUsageLedgerRepository repository = new PostgresUsageLedgerRepository(dataSource);

        repository.append(runId, null, record);

        assertEquals("40:20:5:CONSERVATIVE_ESTIMATE:estimate-v1:FAILED:unavailable",
                queryString("""
                        SELECT input_tokens || ':' || output_tokens || ':' || cached_tokens || ':'
                               || usage_source || ':' || estimator_version || ':' || attempt_outcome || ':'
                               || COALESCE(cost_micros::text, 'unavailable')
                        FROM opspilot.model_usage WHERE model_call_id='%s'
                        """.formatted(invocationId)));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.model_usage SET output_tokens=0 WHERE model_call_id='%s'".formatted(invocationId)));
        assertThrows(PostgresUsageLedgerRepository.UsageLedgerPersistenceException.class,
                () -> repository.append(runId, null, record));
        assertEquals(1, count("opspilot.model_call", "model_call_id", invocationId));
        assertEquals(1, count("opspilot.model_usage", "model_call_id", invocationId));
    }

    @Test
    void realJpaProviderValidatesFlywaySchemaAndMapsCrudJsonTimeDecimalAndOptimisticLock() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID hypothesisId = UUID.randomUUID();
        String targetSystemId = "jpa-" + incidentId;
        execute("INSERT INTO opspilot.target_system (target_system_id,display_name) VALUES ('%s','JPA')"
                .formatted(targetSystemId));

        try (var entityManagerFactory = JpaPersistence.createEntityManagerFactory(dataSource)) {
            try (var entityManager = entityManagerFactory.createEntityManager()) {
                entityManager.getTransaction().begin();
                JpaIncidentRepository incidents = new JpaIncidentRepository(entityManager);
                incidents.save(new JpaIncidentRepository.IncidentSnapshot(
                        new IncidentId(incidentId), targetSystemId, "OPEN", NOW));
                entityManager.persist(new IncidentRunEntity(runId, incidentId, "CREATED", NOW));
                entityManager.persist(new AgentStateEntity(
                        runId, "1.0.0", "{\"schemaVersion\":\"1.0.0\",\"version\":0}", 0, NOW));
                entityManager.persist(new HypothesisEntity(
                        hypothesisId, runId, "database mapping", "PROPOSED",
                        new BigDecimal("0.6250"), NOW, NOW));
                entityManager.getTransaction().commit();
            }

            try (var entityManager = entityManagerFactory.createEntityManager()) {
                JpaIncidentRepository incidents = new JpaIncidentRepository(entityManager);
                assertEquals("OPEN", incidents.find(new IncidentId(incidentId)).orElseThrow().status());
                assertEquals(NOW, incidents.find(new IncidentId(incidentId)).orElseThrow().createdAt());
                AgentStateEntity agentState = entityManager.find(AgentStateEntity.class, runId);
                assertEquals(
                        new ObjectMapper().readTree("{\"schemaVersion\":\"1.0.0\",\"version\":0}"),
                        new ObjectMapper().readTree(agentState.stateJson()));
                assertEquals(NOW, agentState.updatedAt());
                HypothesisEntity hypothesis = entityManager.find(HypothesisEntity.class, hypothesisId);
                assertEquals(new BigDecimal("0.6250"), hypothesis.confidence());
                assertEquals(NOW, hypothesis.createdAt());
            }

            try (var first = entityManagerFactory.createEntityManager();
                 var second = entityManagerFactory.createEntityManager()) {
                first.getTransaction().begin();
                second.getTransaction().begin();
                IncidentRunEntity firstRun = first.find(IncidentRunEntity.class, runId);
                IncidentRunEntity staleRun = second.find(IncidentRunEntity.class, runId);
                firstRun.changeStatus("PLANNING");
                staleRun.changeStatus("COLLECTING_EVIDENCE");
                first.getTransaction().commit();
                assertThrows(RollbackException.class, second.getTransaction()::commit);
            }

            try (var entityManager = entityManagerFactory.createEntityManager()) {
                entityManager.getTransaction().begin();
                entityManager.remove(entityManager.find(HypothesisEntity.class, hypothesisId));
                entityManager.remove(entityManager.find(AgentStateEntity.class, runId));
                entityManager.remove(entityManager.find(IncidentRunEntity.class, runId));
                entityManager.flush();
                assertTrue(new JpaIncidentRepository(entityManager).delete(new IncidentId(incidentId)));
                entityManager.getTransaction().commit();
            }
        }
        assertEquals(0, count("opspilot.incident", "incident_id", incidentId));
    }

    @Test
    void checkpointRollbackCasAndProjectionReceiptsAreDurable() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        IncidentAgentState state = state(incidentId, runId, 0);
        DomainEvent event = new DomainEvent(UUID.randomUUID(), new RunId(runId), "RUN_CREATED", 0, NOW);
        CallAudit audit = new CallAudit(
                UUID.randomUUID(), new RunId(runId), CallKind.TOOL, "fingerprint", "OK", NOW);
        CheckpointCommand command = new CheckpointCommand(
                UUID.randomUUID(), state, -1, List.of(audit), List.of(), List.of(event));

        PostgresCheckpointUnitOfWork failing = new PostgresCheckpointUnitOfWork(dataSource, point -> {
            if (point == PostgresCheckpointUnitOfWork.FailurePoint.AFTER_AUDIT) {
                throw new IllegalStateException("INJECTED");
            }
        });
        assertThrows(IllegalStateException.class, () -> failing.commit(command));
        assertEquals(0, count("opspilot.agent_state", "run_id", runId));
        assertEquals(0, count("opspilot.call_audit", "run_id", runId));
        assertEquals(0, count("opspilot.outbox_event", "run_id", runId));

        PostgresCheckpointUnitOfWork unit = new PostgresCheckpointUnitOfWork(dataSource);
        unit.commit(command);
        assertEquals(state, unit.load(new RunId(runId)).orElseThrow());
        assertEquals(1, count("opspilot.call_audit", "run_id", runId));
        assertEquals(1, count("opspilot.outbox_event", "run_id", runId));

        IncidentAgentState next = state(incidentId, runId, 1);
        PostgresIncidentAgentStateRepository states = new PostgresIncidentAgentStateRepository(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> saveAfter(start, states, next));
            var second = executor.submit(() -> saveAfter(start, states, next));
            start.countDown();
            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }

        PostgresProjectionReceiptRepository receipts =
                new PostgresProjectionReceiptRepository(dataSource, "sse-projector");
        CommittedEventProjector projector = new CommittedEventProjector(receipts, ignored -> { });
        assertEquals(CommittedEventProjector.ProjectionOutcome.PROJECTED, projector.project(event));
        assertEquals(CommittedEventProjector.ProjectionOutcome.DUPLICATE, projector.project(event));
    }

    @Test
    void relationalGuardsRejectUnknownStatesAppendOnlyMutationAndSealedAnalysisWrites() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID hypothesisId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        execute("INSERT INTO opspilot.evidence (evidence_id,run_id,summary) VALUES ('%s','%s','fact')"
                .formatted(evidenceId, runId));
        execute(("INSERT INTO opspilot.hypothesis (hypothesis_id,run_id,statement,status,confidence) "
                + "VALUES ('%s','%s','cause','PROPOSED',0.5)").formatted(hypothesisId, runId));
        execute("INSERT INTO opspilot.hypothesis_evidence "
                + "(hypothesis_id,evidence_id,relation,rationale_summary) VALUES ('%s','%s','SUPPORTS','why')"
                .formatted(hypothesisId, evidenceId));
        execute("INSERT INTO opspilot.call_audit "
                + "(audit_id,run_id,call_kind,action_fingerprint,outcome_code,occurred_at) "
                + "VALUES ('%s','%s','TOOL','fp','OK','%s')".formatted(UUID.randomUUID(), runId, NOW));

        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.call_audit SET outcome_code='CHANGED' WHERE run_id='%s'".formatted(runId)));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.incident_run SET status='UNKNOWN' WHERE run_id='%s'".formatted(runId)));
        assertThrows(SQLException.class, () -> execute(
                "DELETE FROM opspilot.incident WHERE incident_id='%s'".formatted(incidentId)));

        execute("UPDATE opspilot.incident_run SET analysis_sealed_at='%s' WHERE run_id='%s'"
                .formatted(NOW, runId));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO opspilot.evidence (evidence_id,run_id,summary) VALUES ('%s','%s','late')"
                        .formatted(UUID.randomUUID(), runId)));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.hypothesis SET confidence=0.9 WHERE hypothesis_id='%s'"
                        .formatted(hypothesisId)));
        assertThrows(SQLException.class, () -> execute(
                "DELETE FROM opspilot.hypothesis_evidence WHERE hypothesis_id='%s'"
                        .formatted(hypothesisId)));
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO opspilot.hypothesis_verification "
                        + "(verification_id,hypothesis_id,evidence_id,result,summary) "
                        + "VALUES ('%s','%s','%s','CONFIRMED','late')"
                        .formatted(UUID.randomUUID(), hypothesisId, evidenceId)));
    }

    @Test
    void everyCheckpointFailurePointRollsBackAllWrites() throws Exception {
        for (var failurePoint : PostgresCheckpointUnitOfWork.FailurePoint.values()) {
            UUID incidentId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            insertIncidentRun(incidentId, runId);
            CheckpointFixture fixture = insertCheckpointFixture(runId);
            IncidentAgentState state = state(incidentId, runId, 0);
            CheckpointCommand command = new CheckpointCommand(
                    UUID.randomUUID(), state, -1,
                    List.of(new StepWrite(
                            new StepId(fixture.stepId()), "A2A_ANALYSIS", 0, StepAttemptState.RUNNING, 0,
                            List.of(new StepAttemptWrite(
                                    fixture.attemptId(), new Attempt(1), StepAttemptState.RUNNING,
                                    fixture.remoteTaskId(), "attempt-key", "a".repeat(64), 0)))),
                    List.of(new A2aBindingWrite(
                            fixture.a2aBindingId(), new StepId(fixture.stepId()), fixture.remoteTaskId(),
                            "message-1", "b".repeat(64))),
                    List.of(new CodeSnapshotWrite(
                            fixture.codeSnapshotId(), fixture.repositoryId(), fixture.deploymentRevisionId(),
                            "c".repeat(40), new ArtifactId(fixture.artifactId()))),
                    List.of(new CallAudit(
                            UUID.randomUUID(), new RunId(runId), CallKind.MODEL, "fp", "OK", NOW)),
                    List.of(new ReferenceBinding(
                            UUID.randomUUID(), new RunId(runId), BindingType.ARTIFACT, fixture.artifactId())),
                    List.of(new DomainEvent(
                            UUID.randomUUID(), new RunId(runId), "CHECKPOINT", 0, NOW)));
            var unit = new PostgresCheckpointUnitOfWork(dataSource, point -> {
                if (point == failurePoint) {
                    throw new IllegalStateException("INJECTED_" + point);
                }
            });
            assertThrows(IllegalStateException.class, () -> unit.commit(command));
            assertEquals(0, count("opspilot.agent_state", "run_id", runId));
            assertEquals(0, count("opspilot.incident_step", "run_id", runId));
            assertEquals(0, count("opspilot.code_snapshot", "run_id", runId));
            assertEquals(0, count("opspilot.a2a_binding", "run_id", runId));
            assertEquals(0, count("opspilot.call_audit", "run_id", runId));
            assertEquals(0, count("opspilot.reference_binding", "run_id", runId));
            assertEquals(0, count("opspilot.outbox_event", "run_id", runId));
            assertEquals("0", queryString(
                    "SELECT run_version::text FROM opspilot.incident_run WHERE run_id='" + runId + "'"));
        }
    }

    @Test
    void completeCheckpointPersistsEveryWriteAndRejectsCrossRunReferences() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        CheckpointFixture fixture = insertCheckpointFixture(runId);
        IncidentAgentState state = state(incidentId, runId, 0);
        CheckpointCommand command = new CheckpointCommand(
                UUID.randomUUID(), state, -1,
                List.of(new StepWrite(
                        new StepId(fixture.stepId()), "A2A_ANALYSIS", 0, StepAttemptState.RUNNING, 0,
                        List.of(new StepAttemptWrite(
                                fixture.attemptId(), new Attempt(1), StepAttemptState.RUNNING,
                                fixture.remoteTaskId(), "attempt-key", "a".repeat(64), 0)))),
                List.of(new A2aBindingWrite(
                        fixture.a2aBindingId(), new StepId(fixture.stepId()), fixture.remoteTaskId(),
                        "message-1", "b".repeat(64))),
                List.of(new CodeSnapshotWrite(
                        fixture.codeSnapshotId(), fixture.repositoryId(), fixture.deploymentRevisionId(),
                        "c".repeat(40), new ArtifactId(fixture.artifactId()))),
                List.of(new CallAudit(
                        UUID.randomUUID(), new RunId(runId), CallKind.A2A, "fp", "OK", NOW)),
                List.of(new ReferenceBinding(
                        UUID.randomUUID(), new RunId(runId), BindingType.ARTIFACT, fixture.artifactId())),
                List.of(new DomainEvent(
                        UUID.randomUUID(), new RunId(runId), "CHECKPOINT", 0, NOW)));

        new PostgresCheckpointUnitOfWork(dataSource).commit(command);

        assertEquals(1, count("opspilot.agent_state", "run_id", runId));
        assertEquals(1, count("opspilot.incident_step", "run_id", runId));
        assertEquals(1, count("opspilot.step_attempt", "step_id", fixture.stepId()));
        assertEquals(1, count("opspilot.code_snapshot", "run_id", runId));
        assertEquals(1, count("opspilot.a2a_binding", "run_id", runId));
        assertEquals(1, count("opspilot.call_audit", "run_id", runId));
        assertEquals(1, count("opspilot.reference_binding", "run_id", runId));
        assertEquals(1, count("opspilot.outbox_event", "run_id", runId));

        UUID otherIncidentId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        insertIncidentRun(otherIncidentId, otherRunId);
        CheckpointCommand crossRun = new CheckpointCommand(
                UUID.randomUUID(), state(otherIncidentId, otherRunId, 0), -1,
                List.of(),
                List.of(new ReferenceBinding(
                        UUID.randomUUID(), new RunId(otherRunId), BindingType.ARTIFACT, fixture.artifactId())),
                List.of());
        assertThrows(CheckpointConflict.class,
                () -> new PostgresCheckpointUnitOfWork(dataSource).commit(crossRun));
        assertEquals(0, count("opspilot.agent_state", "run_id", otherRunId));
        assertEquals("0", queryString(
                "SELECT run_version::text FROM opspilot.incident_run WHERE run_id='" + otherRunId + "'"));
    }

    @Test
    void taskLeasesAndSseReplayRemainRunScoped() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        insertIncidentRun(UUID.randomUUID(), otherRunId);
        UUID taskId = UUID.randomUUID();
        execute("""
                INSERT INTO opspilot.task
                    (task_id,run_id,task_type,status,priority,available_at,max_attempts,
                     idempotency_key,payload_json)
                VALUES ('%s','%s','PROJECT','PENDING',10,'%s',3,'lease-key',
                        '{"schemaVersion":"1.0.0"}')
                """.formatted(taskId, runId, NOW));

        DurableTaskRepository tasks = new DurableTaskRepository(dataSource);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAfter(start, tasks, "worker-a"));
            var second = executor.submit(() -> claimAfter(start, tasks, "worker-b"));
            start.countDown();
            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }
        execute("UPDATE opspilot.task SET lease_until='%s' WHERE task_id='%s'".formatted(NOW.minusSeconds(1), taskId));
        assertEquals(1, tasks.markExpiredForRecovery(NOW));
        assertEquals("RECOVERING", queryString(
                "SELECT status FROM opspilot.task WHERE task_id='" + taskId + "'"));
        execute("UPDATE opspilot.task SET status='LEASED', attempt_count=max_attempts, "
                + "lease_owner='worker-a', lease_until='%s' "
                .formatted(NOW.minusSeconds(1)) + "WHERE task_id='%s'".formatted(taskId));
        tasks.markExpiredForRecovery(NOW);
        assertEquals("FAILED", queryString(
                "SELECT status FROM opspilot.task WHERE task_id='" + taskId + "'"));
        execute("UPDATE opspilot.task SET status='LEASED', attempt_count=1, lease_owner='worker-a', "
                + "side_effect_committed_at='%s', lease_until='%s' WHERE task_id='%s'"
                .formatted(NOW.minusSeconds(2), NOW.minusSeconds(1), taskId));
        tasks.markExpiredForRecovery(NOW);
        assertEquals("COMPLETED", queryString(
                "SELECT status FROM opspilot.task WHERE task_id='" + taskId + "'"));

        SseEventRepository events = new SseEventRepository(dataSource);
        UUID firstId = UUID.randomUUID();
        events.append(new SseEventRepository.Event(
                firstId, runId, 1, "ONE", json(), NOW));
        events.append(new SseEventRepository.Event(
                UUID.randomUUID(), runId, 2, "TWO", json(), NOW.plusSeconds(1)));
        events.append(new SseEventRepository.Event(
                UUID.randomUUID(), otherRunId, 1, "OTHER", json(), NOW));
        assertEquals(List.of("TWO"), events.replayAfter(runId, firstId, 10).stream()
                .map(SseEventRepository.Event::eventType).toList());
        assertTrue(events.replayAfter(otherRunId, firstId, 10).isEmpty());
    }

    @Test
    void artifactBoundaryIsImmutableAuthorizedAndRecoverable(@TempDir Path root) throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        MutableClock clock = new MutableClock(NOW);
        LocalVolumeArtifactAccessService artifacts =
                new LocalVolumeArtifactAccessService(dataSource, root, 1024, clock,
                        LocalVolumeArtifactAccessService.AccessAuthorizer.sameRun());
        UUID artifactId = UUID.randomUUID();
        byte[] body = "verified artifact".getBytes(StandardCharsets.UTF_8);
        var request = new WriteRequest(
                artifactId, runId, null, "text/plain", AccessLevel.RUN_PRIVATE,
                RetentionClass.RAW_OBSERVATION, new ByteArrayInputStream(body));
        var metadata = artifacts.store(request);
        assertArrayEquals(body, artifacts.read(artifactId, new AccessContext(runId, null, false, false)));
        assertThrows(ArtifactAccessDeniedException.class,
                () -> artifacts.read(artifactId, new AccessContext(UUID.randomUUID(), null, false, false)));
        assertThrows(ArtifactConflictException.class, () -> artifacts.store(new WriteRequest(
                artifactId, runId, null, "text/plain", AccessLevel.RUN_PRIVATE,
                RetentionClass.RAW_OBSERVATION, new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)))));
        assertThrows(ArtifactPathException.class, () -> artifacts.resolveObjectKey("../escape"));
        assertThrows(ArtifactPathException.class, () -> artifacts.resolveObjectKey("NUL"));

        Files.write(root.resolve(metadata.objectKey()), "drift".getBytes(StandardCharsets.UTF_8));
        assertTrue(artifacts.reconcile().stream().anyMatch(issue -> issue.type() == IssueType.HASH_OR_SIZE_DRIFT));
        Files.write(root.resolve(metadata.objectKey()), body);
        clock.advance(Duration.ofDays(14));
        artifacts.deleteExpired(artifactId);
        assertFalse(Files.exists(root.resolve(metadata.objectKey())));

        Files.createDirectories(root.resolve("orphan"));
        Files.writeString(root.resolve("orphan/object"), "orphan");
        assertTrue(artifacts.reconcile().stream().anyMatch(issue -> issue.type() == IssueType.OBJECT_WITHOUT_METADATA));
    }

    @Test
    void a2aAttemptRepositoryPersistsBeforeBindingAndRejectsStaleOrCrossAttemptUpdates()
            throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        execute("""
                INSERT INTO opspilot.incident_step
                    (step_id,run_id,step_type,status,ordinal,version)
                VALUES ('%s','%s','A2A_TEST','PENDING',0,0);
                INSERT INTO opspilot.agent_endpoint (server_agent_id,endpoint_uri,state)
                VALUES ('diagnosis-agent','https://diagnosis.example/a2a','READY')
                ON CONFLICT (server_agent_id) DO NOTHING
                """.formatted(stepId, runId));
        var repository = new PostgresA2aAttemptRepository(dataSource);
        UUID firstId = UUID.randomUUID();
        var first = repository.create(new AttemptDraft(
                firstId, runId, stepId, 1, "message-1", "a".repeat(64),
                "diagnosis-agent", "diagnosis:attempt-1", NOW));
        assertEquals(StepAttemptState.DISPATCHING, first.status());
        assertFalse(first.bound());

        first = repository.bind(firstId, 0,
                new RemoteBinding("diagnosis-agent", "remote-task-1", A2aTaskState.SUBMITTED));
        assertEquals(StepAttemptState.QUEUED, first.status());
        assertThrows(AttemptConflict.class, () -> repository.observe(
                firstId, 0, "remote-task-1", A2aTaskState.WORKING, 0));
        first = repository.observe(
                firstId, first.version(), "remote-task-1", A2aTaskState.WORKING, 1);
        assertEquals(StepAttemptState.RUNNING, first.status());
        var failed = repository.observe(
                firstId, first.version(), "remote-task-1", A2aTaskState.FAILED, 2);
        assertEquals(StepAttemptState.FAILED, failed.status());

        UUID secondId = UUID.randomUUID();
        repository.create(new AttemptDraft(
                secondId, runId, stepId, 2, "message-2", "b".repeat(64),
                "diagnosis-agent", "diagnosis:attempt-2", NOW.plusSeconds(1)));
        assertThrows(AttemptConflict.class, () -> repository.observe(
                firstId, failed.version(), "remote-task-2", A2aTaskState.COMPLETED, 3));
        assertEquals(StepAttemptState.DISPATCHING, repository.find(secondId).orElseThrow().status());

        var claimed = repository.claimRecoverable(
                "supervisor-restart", NOW.plusSeconds(60), 10, NOW.plusSeconds(2));
        assertTrue(claimed.stream().anyMatch(value -> value.attemptId().equals(secondId)));
        assertEquals(StepAttemptState.RECONCILING,
                repository.find(secondId).orElseThrow().status());
    }

    @Test
    void productApiIdempotencyOwnershipAndSseReplayAreDurable() throws Exception {
        PostgresProductApiRepository repository = new PostgresProductApiRepository(dataSource);
        String principal = "tenant-a";
        String key = "create-incident-0001";
        String hash = "a".repeat(64);
        UUID incidentId = UUID.randomUUID();
        AtomicInteger sideEffects = new AtomicInteger();

        var first = repository.executeIdempotent(principal, "createIncident", key, hash, connection -> {
            sideEffects.incrementAndGet();
            repository.createIncident(connection, principal, incidentId, "api-target", List.of("service:a"),
                    null, "API incident", "HIGH", "{}", List.of(), NOW);
            return new PostgresProductApiRepository.StoredResponse(201,
                    Map.of("Content-Type", "application/json"),
                    ("{\"incidentId\":\"" + incidentId + "\"}").getBytes(StandardCharsets.UTF_8));
        });
        var replay = repository.executeIdempotent(principal, "createIncident", key, hash,
                ignored -> { throw new AssertionError("replay must not repeat the side effect"); });

        assertEquals(201, replay.status());
        assertArrayEquals(first.body(), replay.body());
        assertEquals(1, sideEffects.get());
        assertEquals(1, count("opspilot.incident", "incident_id", incidentId));
        assertThrows(PostgresProductApiRepository.Conflict.class,
                () -> repository.executeIdempotent(principal, "createIncident", key,
                        "b".repeat(64), ignored -> first));

        UUID rolledBackIncident = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> repository.executeIdempotent(
                principal, "createIncident", "failed-recovery-01", "c".repeat(64), connection -> {
                    repository.createIncident(connection, principal, rolledBackIncident, "rollback-target",
                            List.of(), null, "Rollback", "LOW", "{}", List.of(), NOW);
                    throw new IllegalStateException("injected");
                }));
        assertEquals(0, count("opspilot.incident", "incident_id", rolledBackIncident));
        repository.executeIdempotent(principal, "createIncident", "failed-recovery-01",
                "c".repeat(64), connection -> {
                    repository.createIncident(connection, principal, rolledBackIncident, "rollback-target",
                            List.of(), null, "Recovered", "LOW", "{}", List.of(), NOW);
                    return new PostgresProductApiRepository.StoredResponse(201, Map.of(), new byte[0]);
                });
        assertEquals(1, count("opspilot.incident", "incident_id", rolledBackIncident));

        UUID concurrentIncident = UUID.randomUUID();
        AtomicInteger concurrentEffects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                return repository.executeIdempotent(principal, "createIncident", "concurrent-key-01",
                        "d".repeat(64), connection -> {
                            concurrentEffects.incrementAndGet();
                            repository.createIncident(connection, principal, concurrentIncident,
                                    "concurrent-target", List.of(), null, "Concurrent", "MEDIUM",
                                    "{}", List.of(), NOW);
                            return new PostgresProductApiRepository.StoredResponse(201, Map.of(), new byte[0]);
                        });
            });
            var right = executor.submit(() -> {
                start.await();
                return repository.executeIdempotent(principal, "createIncident", "concurrent-key-01",
                        "d".repeat(64), connection -> {
                            concurrentEffects.incrementAndGet();
                            repository.createIncident(connection, principal, concurrentIncident,
                                    "concurrent-target", List.of(), null, "Concurrent", "MEDIUM",
                                    "{}", List.of(), NOW);
                            return new PostgresProductApiRepository.StoredResponse(201, Map.of(), new byte[0]);
                        });
            });
            start.countDown();
            assertEquals(201, left.get().status());
            assertEquals(201, right.get().status());
        }
        assertEquals(1, concurrentEffects.get());

        UUID runId = UUID.randomUUID();
        repository.executeIdempotent(principal, "startIncidentRun", "start-run-key-0001",
                "e".repeat(64), connection -> {
                    repository.startRun(connection, principal, incidentId, runId,
                            "models-v1", "mvp-v1", 1_000L, 600);
                    return new PostgresProductApiRepository.StoredResponse(202, Map.of(), new byte[0]);
                });
        assertTrue(repository.findRun(principal, incidentId, runId).isPresent());
        assertTrue(repository.findRun("tenant-b", incidentId, runId).isEmpty());
        assertThrows(PostgresProductApiRepository.NotFound.class,
                () -> repository.toolCalls("tenant-b", incidentId, runId, 100));
        assertThrows(PostgresProductApiRepository.ReportNotReady.class,
                () -> repository.report(principal, incidentId, runId));

        execute("INSERT INTO opspilot.sse_event (event_id,run_id,sequence_no,event_type,payload_json) "
                + "VALUES ('%s','%s',101,'RUN_STARTED','{\"schemaVersion\":\"1.0.0\"}'),"
                .formatted(UUID.randomUUID(), runId)
                + "('%s','%s',102,'STEP_STARTED','{\"schemaVersion\":\"1.0.0\"}')"
                .formatted(UUID.randomUUID(), runId));
        assertEquals(List.of(102L), repository.eventsAfter(principal, incidentId, runId, 101, 100)
                .stream().map(PostgresProductApiRepository.EventSnapshot::sequence).toList());

        UUID otherIncident = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        repository.executeIdempotent(principal, "createIncident", "other-incident-001",
                "f".repeat(64), connection -> {
                    repository.createIncident(connection, principal, otherIncident, "other-target",
                            List.of(), null, "Other", "LOW", "{}", List.of(), NOW);
                    return new PostgresProductApiRepository.StoredResponse(201, Map.of(), new byte[0]);
                });
        repository.executeIdempotent(principal, "startIncidentRun", "other-start-run-01",
                "0".repeat(64), connection -> {
                    repository.startRun(connection, principal, otherIncident, otherRun,
                            "models-v1", "mvp-v1", null, 600);
                    return new PostgresProductApiRepository.StoredResponse(202, Map.of(), new byte[0]);
                });
        assertThrows(PostgresProductApiRepository.NotFound.class,
                () -> repository.eventsAfter(principal, otherIncident, otherRun, 101, 100));
    }

    @Test
    void correlationAuditIsCompleteAppendOnlyRedactedAndOwnershipChecked(@TempDir Path root)
            throws Exception {
        String principal = "correlation-tenant";
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PostgresProductApiRepository product = new PostgresProductApiRepository(dataSource);
        product.executeIdempotent(principal, "createIncident", "correlation-create-01",
                "1".repeat(64), connection -> {
                    product.createIncident(connection, principal, incidentId, "correlation-target",
                            List.of(), null, "Correlation", "HIGH", "{}", List.of(), NOW);
                    return new PostgresProductApiRepository.StoredResponse(201, Map.of(), new byte[0]);
                });
        product.executeIdempotent(principal, "startIncidentRun", "correlation-start-001",
                "2".repeat(64), connection -> {
                    product.startRun(connection, principal, incidentId, runId,
                            "models-v1", "mvp-v1", 1_000L, 600);
                    return new PostgresProductApiRepository.StoredResponse(202, Map.of(), new byte[0]);
                });

        CorrelationContext context = CorrelationContext.ingress(principal)
                .withIncident(incidentId).withRun(runId).withStep(UUID.randomUUID())
                .withA2aTask("a2a-correlation-task").withInvocation(UUID.randomUUID());
        LocalVolumeArtifactAccessService artifactService =
                new LocalVolumeArtifactAccessService(dataSource, root, 32_768);
        PostgresCorrelationAuditRepository audit =
                new PostgresCorrelationAuditRepository(dataSource, artifactService);
        UUID parent = null;
        UUID logArtifactId = null;
        List<String> boundaries = List.of("REST", "SUPERVISOR", "A2A", "AGENT_RUNTIME",
                "TOOL", "PROVIDER", "ARTIFACT", "EVIDENCE", "ANALYSIS_SEAL", "RCA", "SSE");
        for (int index = 0; index < boundaries.size(); index++) {
            String boundary = boundaries.get(index);
            String rawLog = "TOOL".equals(boundary)
                    ? "authorization=Bearer-secret prompt=private diagnostic payload" : null;
            var event = audit.append(new PostgresCorrelationAuditRepository.AuditDraft(
                    parent, context, boundary, "a".repeat(64),
                    Map.of("decision", "allowed"),
                    "ARTIFACT".equals(boundary) ? "REJECTED" : "SUCCEEDED",
                    "ARTIFACT".equals(boundary) ? "ARTIFACT_HASH_MISMATCH" : null,
                    boundary + " boundary outcome", NOW.plusSeconds(index)), rawLog);
            parent = event.auditId();
            if (event.logArtifactId() != null) logArtifactId = event.logArtifactId();
        }

        List<PostgresCorrelationAuditRepository.AuditEvent> chain =
                audit.findOwnedChain(principal, incidentId, context.requestId());
        assertEquals(boundaries, chain.stream().map(
                PostgresCorrelationAuditRepository.AuditEvent::boundary).toList());
        assertTrue(chain.stream().allMatch(event -> event.context().traceId().equals(context.traceId())));
        assertTrue(chain.stream().noneMatch(event -> event.summary().contains("Bearer-secret")));
        assertTrue(logArtifactId != null);
        assertTrue(new String(audit.readOwnedLog(principal, incidentId, runId, logArtifactId),
                StandardCharsets.UTF_8).contains("Bearer-secret"));
        UUID controlledLogId = logArtifactId;
        assertThrows(PostgresCorrelationAuditRepository.AuditNotFound.class,
                () -> audit.readOwnedLog("other-tenant", incidentId, runId, controlledLogId));
        assertTrue(audit.findOwnedChain("other-tenant", incidentId, context.requestId()).isEmpty());
        assertThrows(PostgresCorrelationAuditRepository.AuditNotFound.class,
                () -> audit.append(new PostgresCorrelationAuditRepository.AuditDraft(
                        null, new CorrelationContext("other-tenant", context.requestId(), context.traceId(),
                                incidentId, runId, null, null, null), "REST", "b".repeat(64),
                        Map.of(), "REJECTED", "RESOURCE_NOT_FOUND", "not found", NOW), null));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.correlation_audit SET summary='mutated' WHERE audit_id='"
                        + chain.getFirst().auditId() + "'"));

        CorrelationContext failureContext = CorrelationContext.ingress(principal)
                .withIncident(incidentId).withRun(runId).withStep(UUID.randomUUID());
        List<String> artifactErrors = List.of(
                "ARTIFACT_MEDIA_TYPE_INVALID", "ARTIFACT_SCHEMA_MAJOR_UNSUPPORTED",
                "ARTIFACT_SCHEMA_INVALID", "ARTIFACT_SOURCE_WRONG_RUN",
                "ARTIFACT_OWNERSHIP_INVALID", "ARTIFACT_HASH_MISMATCH",
                "ARTIFACT_REFERENCE_FORBIDDEN", "ARTIFACT_DOMAIN_INVALID");
        UUID failureParent = null;
        for (int index = 0; index < artifactErrors.size(); index++) {
            var event = audit.append(new PostgresCorrelationAuditRepository.AuditDraft(
                    failureParent, failureContext, "ARTIFACT", "c".repeat(64),
                    Map.of("decision", "denied"), "REJECTED", artifactErrors.get(index),
                    "Artifact validation rejected at layer " + (index + 1),
                    NOW.plusSeconds(100 + index)), null);
            failureParent = event.auditId();
        }
        for (String resultCode : List.of("FAILED", "CANCELLED")) {
            var event = audit.append(new PostgresCorrelationAuditRepository.AuditDraft(
                    failureParent, failureContext, "A2A", "d".repeat(64),
                    Map.of("decision", "allowed"), resultCode,
                    resultCode + "_BY_POLICY", "Stable " + resultCode.toLowerCase() + " outcome",
                    NOW.plusSeconds(110)), null);
            failureParent = event.auditId();
        }
        List<PostgresCorrelationAuditRepository.AuditEvent> failureChain =
                audit.findOwnedChain(principal, incidentId, failureContext.requestId());
        assertEquals(10, failureChain.size());
        assertEquals(artifactErrors, failureChain.stream().limit(8)
                .map(PostgresCorrelationAuditRepository.AuditEvent::errorCode).toList());
        assertTrue(failureChain.stream().allMatch(event -> event.logArtifactId() == null));
        assertEquals(java.util.Set.of("FAILED", "CANCELLED"), failureChain.stream().skip(8)
                .map(PostgresCorrelationAuditRepository.AuditEvent::resultCode).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void supervisorDelegationArtifactReceptionSealAndConsistentReadAreDurable() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID inputEvidenceId = UUID.randomUUID();
        UUID inputArtifactId = UUID.randomUUID();
        insertIncidentRun(incidentId, runId);
        execute("""
                INSERT INTO opspilot.artifact
                    (artifact_id,run_id,uri,sha256,media_type,access_level,object_key,size_bytes)
                VALUES ('%s','%s','artifact://input','%s','application/json','RUN_PRIVATE','input/%s',0);
                INSERT INTO opspilot.evidence
                    (evidence_id,run_id,summary,artifact_id,attributes)
                VALUES ('%s','%s','input fact','%s','{"schemaVersion":"1.0.0"}')
                """.formatted(inputArtifactId, runId, "a".repeat(64), inputArtifactId,
                inputEvidenceId, runId, inputArtifactId));

        PostgresSupervisorEvidenceStore store = new PostgresSupervisorEvidenceStore(dataSource);
        var delegation = new SupervisorOrchestrationService.DelegationRecord(
                new RunId(runId), new StepId(stepId), 1, UUID.randomUUID().toString(),
                "evidence-agent", "collect-runtime-evidence",
                List.of(new EvidenceId(inputEvidenceId)), List.of(new ArtifactId(inputArtifactId)),
                new SupervisorOrchestrationService.RemainingBudget(2, 3, 4, 500, 600),
                NOW.plusSeconds(300), "capability-digest", NOW);
        store.commitBeforeNetwork(delegation);
        assertEquals("collect-runtime-evidence", queryString(
                "SELECT target_skill FROM opspilot.step_attempt WHERE step_id='" + stepId + "'"));
        assertEquals("capability-digest", queryString(
                "SELECT capability_snapshot_json->>'digest' FROM opspilot.step_attempt WHERE step_id='"
                        + stepId + "'"));
        assertEquals(1, queryInt("SELECT cardinality(input_evidence_ids) FROM opspilot.step_attempt WHERE step_id='"
                + stepId + "'"));

        UUID taskId = UUID.randomUUID();
        execute("""
                INSERT INTO opspilot.task
                    (task_id,run_id,task_type,status,max_attempts,idempotency_key,payload_json)
                VALUES ('%s','%s','A2A_RESULT','COMPLETED',1,'artifact-task',
                        '{"schemaVersion":"1.0.0"}')
                """.formatted(taskId, runId));
        UUID artifactId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID hypothesisId = UUID.randomUUID();
        byte[] payload = "{\"schemaVersion\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        var invalidMutation = new ArtifactReceiver.ReceptionMutation(
                new ArtifactId(artifactId), new RunId(runId),
                List.of(new ArtifactReceiver.EvidenceWrite(new EvidenceId(evidenceId), "remote fact")),
                List.of(new ArtifactReceiver.HypothesisWrite(
                        new HypothesisId(hypothesisId), "root cause", List.of(new EvidenceId(evidenceId)))),
                List.of(new ArtifactReceiver.RelationWrite(
                        new HypothesisId(hypothesisId), new EvidenceId(UUID.randomUUID()), "SUPPORTS")),
                List.of(), List.of(ArtifactReceiver.RawFactKind.EVIDENCE), UUID.randomUUID());
        var invalid = ArtifactReceiver.RemoteArtifact.json(
                new ArtifactId(artifactId), new RunId(runId), taskId, payload,
                List.of(new EvidenceId(evidenceId)), invalidMutation);
        assertThrows(IllegalStateException.class, () -> store.commit(invalid));
        assertEquals(0, count("opspilot.artifact", "artifact_id", artifactId));
        assertEquals(0, count("opspilot.evidence", "evidence_id", evidenceId));
        assertEquals(0, count("opspilot.hypothesis", "hypothesis_id", hypothesisId));

        UUID verificationId = UUID.randomUUID();
        var validMutation = new ArtifactReceiver.ReceptionMutation(
                new ArtifactId(artifactId), new RunId(runId), invalidMutation.evidence(),
                invalidMutation.hypotheses(), List.of(new ArtifactReceiver.RelationWrite(
                        new HypothesisId(hypothesisId), new EvidenceId(evidenceId), "SUPPORTS")),
                List.of(new ArtifactReceiver.VerificationWrite(
                        verificationId, new HypothesisId(hypothesisId), new EvidenceId(evidenceId),
                        "CONFIRMED", "checked")),
                List.of(ArtifactReceiver.RawFactKind.EVIDENCE), UUID.randomUUID());
        var valid = ArtifactReceiver.RemoteArtifact.json(
                new ArtifactId(artifactId), new RunId(runId), taskId, payload,
                List.of(new EvidenceId(evidenceId)), validMutation);
        store.commit(valid);
        store.commit(valid);
        assertEquals(1, count("opspilot.artifact_reception", "artifact_id", artifactId));
        assertEquals(1, count("opspilot.evidence", "evidence_id", evidenceId));
        assertEquals(1, count("opspilot.hypothesis", "hypothesis_id", hypothesisId));
        assertEquals(1, count("opspilot.hypothesis_verification", "verification_id", verificationId));

        execute("UPDATE opspilot.step_attempt SET status='COMPLETED' WHERE step_id='" + stepId + "'");
        var sealed = store.sealAtomically(new RunId(runId), 0, NOW.plusSeconds(1), List.of());
        assertEquals(1, sealed.runVersion());
        var reportInput = store.inReadOnlyTransaction(new RunId(runId), 1);
        assertEquals(List.of(new EvidenceId(inputEvidenceId), new EvidenceId(evidenceId)).stream().sorted(
                        java.util.Comparator.comparing(EvidenceId::wire)).toList(),
                reportInput.evidence().stream().map(value -> value.evidenceId()).toList());
        assertEquals(1, reportInput.hypotheses().size());
        assertEquals(1, reportInput.relations().size());
        assertEquals(1, reportInput.verifications().size());

        UUID lateArtifactId = UUID.randomUUID();
        UUID lateEvidenceId = UUID.randomUUID();
        var lateMutation = new ArtifactReceiver.ReceptionMutation(
                new ArtifactId(lateArtifactId), new RunId(runId),
                List.of(new ArtifactReceiver.EvidenceWrite(new EvidenceId(lateEvidenceId), "late")),
                List.of(), List.of(), List.of(), List.of(ArtifactReceiver.RawFactKind.EVIDENCE), UUID.randomUUID());
        var late = ArtifactReceiver.RemoteArtifact.json(
                new ArtifactId(lateArtifactId), new RunId(runId), taskId, payload,
                List.of(new EvidenceId(lateEvidenceId)), lateMutation);
        assertThrows(IllegalStateException.class, () -> store.commit(late));
        assertEquals(0, count("opspilot.artifact", "artifact_id", lateArtifactId));
        assertEquals("1", queryString(
                "SELECT run_version::text FROM opspilot.incident_run WHERE run_id='" + runId + "'"));
    }

    private static boolean saveAfter(
            CountDownLatch start, PostgresIncidentAgentStateRepository repository, IncidentAgentState state)
            throws InterruptedException {
        start.await();
        try {
            repository.save(state, 0);
            return true;
        } catch (PostgresIncidentAgentStateRepository.StateCasConflictException expected) {
            return false;
        }
    }

    private static boolean claimAfter(CountDownLatch start, DurableTaskRepository tasks, String worker)
            throws InterruptedException {
        start.await();
        return tasks.claim(worker, NOW, Duration.ofMinutes(1)).isPresent();
    }

    private static IncidentAgentState state(UUID incidentId, UUID runId, long version) {
        return new IncidentAgentState(
                IncidentAgentState.CURRENT_SCHEMA_VERSION, new IncidentId(incidentId), new RunId(runId),
                "context", UUID.randomUUID(), version, IncidentRunState.CREATED, null, null, 0,
                null, List.of(), List.of(), List.of(), null, null,
                new TokenBudgetSnapshot(100, 0), new UsageStatistics(0, 0, 0),
                new ReactLoopSnapshot(0, 0, 0, 0, List.of(), List.of()),
                List.of(), List.of(), null, null, false, NOW.plusSeconds(3600), NOW, NOW);
    }

    private static void insertIncidentRun(UUID incidentId, UUID runId) throws SQLException {
        execute("""
                INSERT INTO opspilot.incident (incident_id,target_system_id,status)
                VALUES ('%s','sample-system','OPEN');
                INSERT INTO opspilot.incident_run (run_id,incident_id,status)
                VALUES ('%s','%s','CREATED')
                """.formatted(incidentId, runId, incidentId));
    }

    private static CheckpointFixture insertCheckpointFixture(UUID runId) throws SQLException {
        UUID artifactId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID codeSourceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID deploymentRevisionId = UUID.randomUUID();
        UUID remoteTaskId = UUID.randomUUID();
        String configHash = codeSourceId.toString().replace("-", "").repeat(2);
        execute("""
                INSERT INTO opspilot.artifact
                    (artifact_id,run_id,uri,sha256,media_type,access_level,object_key,size_bytes)
                VALUES ('%s','%s','artifact://%s','%s','application/json','RUN_PRIVATE','%s',0);
                INSERT INTO opspilot.resource (resource_id,target_system_id,resource_type,external_key)
                VALUES ('%s','sample-system','SERVICE','resource-%s');
                INSERT INTO opspilot.code_source
                    (code_source_id,target_system_id,source_type,config_identity_sha256,config_json)
                VALUES ('%s','sample-system','GIT','%s','{"schemaVersion":"1.0.0"}');
                INSERT INTO opspilot.code_repository
                    (repository_id,code_source_id,external_key,default_branch)
                VALUES ('%s','%s','repo-%s','main');
                INSERT INTO opspilot.deployment_revision
                    (deployment_revision_id,resource_id,repository_id,commit_sha,image_digest,deployed_at)
                VALUES ('%s','%s','%s','%s','sha256:%s','%s');
                INSERT INTO opspilot.agent_endpoint (server_agent_id,endpoint_uri,state)
                VALUES ('agent-one','https://agent.example/a2a','READY')
                ON CONFLICT (server_agent_id) DO NOTHING
                """.formatted(
                artifactId, runId, artifactId, "d".repeat(64), artifactId,
                resourceId, resourceId,
                codeSourceId, configHash,
                repositoryId, codeSourceId, repositoryId,
                deploymentRevisionId, resourceId, repositoryId, "c".repeat(40), "f".repeat(64), NOW));
        return new CheckpointFixture(
                artifactId, repositoryId, deploymentRevisionId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                new RemoteTaskId("agent-one", new A2aTaskId(remoteTaskId)));
    }

    private record CheckpointFixture(
            UUID artifactId,
            UUID repositoryId,
            UUID deploymentRevisionId,
            UUID codeSnapshotId,
            UUID stepId,
            UUID attemptId,
            UUID a2aBindingId,
            RemoteTaskId remoteTaskId) {
    }

    private static int count(String table, String column, UUID value) throws SQLException {
        return queryInt("SELECT count(*) FROM " + table + " WHERE " + column + "='" + value + "'");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            return queryInt(statement, sql);
        }
    }

    private static int queryInt(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean queryBoolean(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String resource(String name) throws IOException {
        try (var stream = PostgresPhase3AdapterTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String json() {
        return "{\"schemaVersion\":\"1.0.0\"}";
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
