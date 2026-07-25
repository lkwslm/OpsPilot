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
import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.ReactLoopSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.UsageStatistics;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
        assertTrue(new PostgresReadinessCheck(dataSource, "8", "0.8.4").check().ready());
        assertEquals("FLYWAY_VERSION_MISMATCH",
                new PostgresReadinessCheck(dataSource, "99", "0.8.4").check().reason());
        assertEquals("PGVECTOR_VERSION_MISMATCH",
                new PostgresReadinessCheck(dataSource, "8", "99").check().reason());

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
