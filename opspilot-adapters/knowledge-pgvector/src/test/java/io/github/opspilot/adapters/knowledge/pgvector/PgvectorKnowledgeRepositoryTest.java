package io.github.opspilot.adapters.knowledge.pgvector;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.opspilot.core.application.incident.SupervisorOrchestrationService;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.DelegationRecord;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.DelegationStore;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.PlanStep;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.RemainingBudget;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.ResultKind;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.Role;
import io.github.opspilot.core.application.incident.SupervisorOrchestrationService.RunSnapshot;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingBatchCommit;
import io.github.opspilot.core.port.repository.EmbeddingBatchCommitPort.EmbeddingVectorWrite;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeReference;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeSnapshot;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.Outcome;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.SearchAudit;
import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.SearchRequest;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.provider.RerankPort.RankedDocument;

import static io.github.opspilot.adapters.knowledge.pgvector.PgvectorKnowledgeRepository.DistanceMetric.COSINE;
import static io.github.opspilot.core.domain.state.StateMachines.IncidentRunState.GENERATING_HYPOTHESES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PgvectorKnowledgeRepositoryTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static PostgreSQLContainer postgres;
    private static DataSource dataSource;

    @Test
    void kbEmptyIsARealDatabaseOutcomeAndFieldInvestigationContinues() throws Exception {
        Fixture fixture = fixture("phase8-kb-empty");
        UUID collectionId = UUID.fromString("3eb2a9f3-6e9b-5439-a772-e2052d505fb5");
        UUID knowledgeRevisionId = UUID.fromString("01e066b2-ee42-57ea-8311-0efa2395537e");
        execute("INSERT INTO opspilot.knowledge_collection (collection_id, collection_key) VALUES (?, ?)",
                collectionId, "phase8-empty-outcome-kb-empty-v1");
        execute("INSERT INTO opspilot.knowledge_revision "
                        + "(knowledge_revision_id, collection_id, status, normalization_version, "
                        + "chunk_strategy_version, model_revision_id, embedding_dimension, coverage_status, "
                        + "expected_chunk_count, completed_chunk_count, searchable) "
                        + "VALUES (?, ?, 'ACTIVE', 'phase8-v1', 'phase8-v1', ?, 3, 'COMPLETE', 0, 0, true)",
                knowledgeRevisionId, collectionId, fixture.revisionId());
        execute("UPDATE opspilot.knowledge_collection SET active_knowledge_revision_id=?, "
                        + "active_model_revision_id=? WHERE collection_id=?",
                knowledgeRevisionId, fixture.revisionId(), collectionId);
        execute("UPDATE opspilot.incident_run SET effective_knowledge_revision_id=? WHERE run_id=?",
                knowledgeRevisionId, fixture.runId());

        var pgvector = new PgvectorKnowledgeSearchAdapter(dataSource, (artifact, location) -> {
            throw new AssertionError("KB_EMPTY must not load Chunk text");
        });
        List<SearchAudit> audits = new ArrayList<>();
        ProviderIdentity embeddingIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-small-zh-v1.5", "7999e1d3359715c523056ef9478215996d62a620");
        ProviderIdentity rerankIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-reranker-v2-m3", "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e");
        var service = new KnowledgeSearchService(
                pgvector,
                request -> { throw new AssertionError("KB_EMPTY must not call Embedding"); },
                pgvector,
                request -> { throw new AssertionError("KB_EMPTY must not call Rerank"); },
                (runId, reference) -> { throw new AssertionError("KB_EMPTY must not persist Citation"); },
                audits::add);
        var response = service.search(new SearchRequest(
                fixture.runId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, 1,
                new KnowledgeSnapshot(collectionId, knowledgeRevisionId, fixture.revisionId(),
                        3, "COSINE", embeddingIdentity, rerankIdentity),
                "库存服务在现场窗口出现健康检查失败和上游连接错误，请检索相关运维资料",
                20, 5,
                Map.of("language", "zh", "service", "inventory-service", "documentType", "playbook"),
                Set.of("phase8-empty-outcome-reader"), Instant.now().plusSeconds(60)));

        assertNull(response.failure());
        assertEquals(Outcome.KB_EMPTY, response.value().outcome());
        assertFalse(response.value().rerankApplied());
        assertTrue(response.value().references().isEmpty());
        assertEquals(1, audits.size());
        assertEquals(0, audits.getFirst().candidateCount());
        assertEquals(0, audits.getFirst().rerankCount());
        assertEquals(0, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_chunk chunk "
                        + "JOIN opspilot.knowledge_document_version version "
                        + "ON version.document_version_id=chunk.document_version_id "
                        + "WHERE version.knowledge_revision_id=? AND chunk.searchable",
                knowledgeRevisionId));
        assertEquals(0, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_reference WHERE run_id=?", fixture.runId()));

        var businessResult = SupervisorOrchestrationService.interpretProfessionalResult(
                Role.SUPERVISOR, ResultKind.KB_EMPTY, null);
        assertTrue(businessResult.succeeded());
        assertTrue(businessResult.missingEvidence().isEmpty());
        List<EvidenceId> fieldEvidence = List.of(
                new EvidenceId(UUID.randomUUID()), new EvidenceId(UUID.randomUUID()));
        List<DelegationRecord> delegated = new ArrayList<>();
        var supervisor = new SupervisorOrchestrationService(new DelegationStore() {
            @Override public void commitBeforeNetwork(DelegationRecord record) { }
            @Override public void markSkipped(RunId runId, StepId stepId, String reasonCode) { }
            @Override public void recordMissingEvidence(RunId runId, String reasonCode) { }
        }, delegated::add);
        var decision = supervisor.dispatchNext(new RunSnapshot(
                new RunId(fixture.runId()), GENERATING_HYPOTHESES,
                List.of(new PlanStep(new StepId(UUID.randomUUID()), Role.KNOWLEDGE),
                        new PlanStep(new StepId(UUID.randomUUID()), Role.DIAGNOSIS)),
                1, 1, false, true, Set.of("field-evidence-present"), 0, 2,
                fieldEvidence, List.of(), new RemainingBudget(3, 3, 3, 1000, 1000),
                Instant.now().plusSeconds(60), "phase8-empty-outcome-v1"));

        assertEquals(SupervisorOrchestrationService.DecisionKind.DELEGATED, decision.kind());
        assertEquals("diagnose-incident", decision.record().targetSkill());
        assertEquals(fieldEvidence, decision.record().evidenceIds());
        assertEquals(1, delegated.size());
    }

    @Test
    void noMatchUsesRealFilteredRecallWithoutRerankFallbackOrCitation() throws Exception {
        Fixture fixture = fixture("phase8-no-match");
        UUID collectionId = UUID.fromString("5fe401bf-25da-5799-8a1a-9f7f5b699f14");
        UUID knowledgeRevisionId = UUID.fromString("c05677a6-f0c9-55c6-b78f-747244e01cc1");
        execute("INSERT INTO opspilot.knowledge_collection (collection_id, collection_key) VALUES (?, ?)",
                collectionId, "phase8-empty-outcome-no-match-v1");
        execute("INSERT INTO opspilot.knowledge_revision "
                        + "(knowledge_revision_id, collection_id, status, normalization_version, "
                        + "chunk_strategy_version, model_revision_id, embedding_dimension, coverage_status, "
                        + "expected_chunk_count, completed_chunk_count, searchable) "
                        + "VALUES (?, ?, 'ACTIVE', 'phase8-v1', 'phase8-v1', ?, 3, 'COMPLETE', 2, 2, true)",
                knowledgeRevisionId, collectionId, fixture.revisionId());

        var versions = new KnowledgeVersionRepository(dataSource);
        var vectors = new PgvectorKnowledgeRepository(dataSource);
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID firstChunk = UUID.randomUUID();
        UUID secondChunk = UUID.randomUUID();
        versions.createDocument(documentId, collectionId, "phase8-no-match-active-document");
        versions.createVersion(versionId, documentId, 1, HASH_A, artifact(fixture.runId()), 2, null);
        versions.appendChunk(firstChunk, versionId, 0, HASH_A, artifact(fixture.runId()),
                "{\"schemaVersion\":\"1.0.0\",\"language\":\"zh\","
                        + "\"service\":\"inventory-service\",\"tag\":\"known-playbook-a\"}");
        versions.appendChunk(secondChunk, versionId, 1, HASH_B, artifact(fixture.runId()),
                "{\"schemaVersion\":\"1.0.0\",\"language\":\"zh\","
                        + "\"service\":\"inventory-service\",\"tag\":\"known-playbook-b\"}");
        execute("UPDATE opspilot.knowledge_document_version SET knowledge_revision_id=?, "
                        + "acl_json='{\"principals\":[\"phase8-empty-outcome-reader\"]}'::jsonb "
                        + "WHERE document_version_id=?", knowledgeRevisionId, versionId);
        vectors.insertEmbedding(firstChunk, fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, HASH_A);
        vectors.insertEmbedding(secondChunk, fixture.revisionId(), 3, COSINE,
                new float[]{0, 1, 0}, HASH_B);
        UUID jobId = UUID.randomUUID();
        versions.createJob(jobId, versionId, fixture.revisionId());
        versions.recordCheckpoint(jobId, 2, 2, "COMPLETE", null);
        versions.activateVersion(versionId, fixture.revisionId());
        execute("UPDATE opspilot.knowledge_collection SET active_knowledge_revision_id=? "
                        + "WHERE collection_id=?", knowledgeRevisionId, collectionId);
        execute("UPDATE opspilot.incident_run SET effective_knowledge_revision_id=? WHERE run_id=?",
                knowledgeRevisionId, fixture.runId());

        var pgvector = new PgvectorKnowledgeSearchAdapter(dataSource, (artifactId, location) -> {
            throw new AssertionError("NO_MATCH must not load fallback Chunk text");
        });
        AtomicInteger catalogCalls = new AtomicInteger();
        AtomicInteger embeddingCalls = new AtomicInteger();
        AtomicInteger recallCalls = new AtomicInteger();
        List<SearchAudit> audits = new ArrayList<>();
        ProviderIdentity embeddingIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-small-zh-v1.5", "7999e1d3359715c523056ef9478215996d62a620");
        ProviderIdentity rerankIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-reranker-v2-m3", "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e");
        var service = new KnowledgeSearchService(
                (snapshot, principals) -> {
                    catalogCalls.incrementAndGet();
                    return pgvector.hasSearchableDocuments(snapshot, principals);
                },
                request -> {
                    embeddingCalls.incrementAndGet();
                    return new ProviderResult<>(List.of(new float[]{1, 0, 0}),
                            new ProviderUsage(1, 0, 0L), null);
                },
                query -> {
                    recallCalls.incrementAndGet();
                    return pgvector.exactCandidates(query);
                },
                request -> { throw new AssertionError("NO_MATCH must not call Rerank"); },
                (runId, reference) -> { throw new AssertionError("NO_MATCH must not persist Citation"); },
                audits::add);
        var response = service.search(new SearchRequest(
                fixture.runId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, 1,
                new KnowledgeSnapshot(collectionId, knowledgeRevisionId, fixture.revisionId(),
                        3, "COSINE", embeddingIdentity, rerankIdentity),
                "检索当前库存调用延迟现场信号对应的隔离租户运维资料",
                20, 5,
                Map.of("language", "zh", "service", "inventory-service",
                        "tag", "phase8-isolated-no-match"),
                Set.of("phase8-empty-outcome-reader"), Instant.now().plusSeconds(60)));

        assertNull(response.failure());
        assertEquals(Outcome.NO_MATCH, response.value().outcome());
        assertFalse(response.value().rerankApplied());
        assertTrue(response.value().references().isEmpty());
        assertEquals(1, catalogCalls.get());
        assertEquals(1, embeddingCalls.get());
        assertEquals(1, recallCalls.get());
        assertEquals(1, audits.size());
        assertEquals(0, audits.getFirst().candidateCount());
        assertEquals(0, audits.getFirst().rerankCount());
        assertEquals(2, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_chunk chunk "
                        + "JOIN opspilot.knowledge_document_version version "
                        + "ON version.document_version_id=chunk.document_version_id "
                        + "WHERE version.knowledge_revision_id=? AND chunk.searchable",
                knowledgeRevisionId));
        assertEquals(0, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_reference WHERE run_id=?", fixture.runId()));
    }

    @Test
    void zeroIncidentHistoryIsNormalAndPreservesFieldEvidenceAndKnowledgeTrace() throws Exception {
        Fixture fixture = fixture("phase8-insufficient-history");
        UUID collectionId = UUID.fromString("6b0f41a0-7593-58e5-9280-ba2e58ee5e5e");
        UUID knowledgeRevisionId = UUID.fromString("c8daf3d4-8664-549e-b27d-52f593e0f784");
        execute("INSERT INTO opspilot.knowledge_collection (collection_id, collection_key) VALUES (?, ?)",
                collectionId, "phase8-empty-outcome-insufficient-history-v1");
        execute("INSERT INTO opspilot.knowledge_revision "
                        + "(knowledge_revision_id, collection_id, status, normalization_version, "
                        + "chunk_strategy_version, model_revision_id, embedding_dimension, coverage_status, "
                        + "expected_chunk_count, completed_chunk_count, searchable) "
                        + "VALUES (?, ?, 'ACTIVE', 'phase8-v1', 'phase8-v1', ?, 3, 'COMPLETE', 3, 3, true)",
                knowledgeRevisionId, collectionId, fixture.revisionId());

        var versions = new KnowledgeVersionRepository(dataSource);
        var vectors = new PgvectorKnowledgeRepository(dataSource);
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        List<UUID> chunks = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> artifacts = List.of(
                artifact(fixture.runId()), artifact(fixture.runId()), artifact(fixture.runId()));
        versions.createDocument(documentId, collectionId, "phase8-history-playbooks");
        versions.createVersion(versionId, documentId, 1, HASH_A, artifact(fixture.runId()), 3, null);
        for (int index = 0; index < chunks.size(); index++) {
            String hash = index == 1 ? HASH_B : HASH_A;
            versions.appendChunk(chunks.get(index), versionId, index, hash, artifacts.get(index),
                    "{\"schemaVersion\":\"1.0.0\",\"language\":\"zh\","
                            + "\"service\":\"order-service\",\"documentType\":\"playbook\"}");
            vectors.insertEmbedding(chunks.get(index), fixture.revisionId(), 3, COSINE,
                    new float[]{index == 0 ? 1 : 0, index == 1 ? 1 : 0, index == 2 ? 1 : 0}, hash);
        }
        execute("UPDATE opspilot.knowledge_document_version SET knowledge_revision_id=?, "
                        + "acl_json='{\"principals\":[\"phase8-empty-outcome-reader\"]}'::jsonb "
                        + "WHERE document_version_id=?", knowledgeRevisionId, versionId);
        UUID jobId = UUID.randomUUID();
        versions.createJob(jobId, versionId, fixture.revisionId());
        versions.recordCheckpoint(jobId, 3, 3, "COMPLETE", null);
        versions.activateVersion(versionId, fixture.revisionId());
        execute("UPDATE opspilot.knowledge_collection SET active_knowledge_revision_id=? "
                        + "WHERE collection_id=?", knowledgeRevisionId, collectionId);
        execute("UPDATE opspilot.incident_run SET effective_knowledge_revision_id=? WHERE run_id=?",
                knowledgeRevisionId, fixture.runId());
        execute("INSERT INTO opspilot.evidence "
                        + "(evidence_id, run_id, summary, evidence_code, attributes) VALUES "
                        + "(?, ?, 'connection waiters present', 'metric.order.connection_pending', "
                        + "'{\"schemaVersion\":\"1.0.0\",\"evidenceCode\":\"metric.order.connection_pending\"}'::jsonb), "
                        + "(?, ?, 'connection timeouts present', 'log.order.connection_timeout', "
                        + "'{\"schemaVersion\":\"1.0.0\",\"evidenceCode\":\"log.order.connection_timeout\"}'::jsonb)",
                UUID.randomUUID(), fixture.runId(), UUID.randomUUID(), fixture.runId());

        Map<UUID, String> textByArtifact = Map.of(
                artifacts.get(0), "连接池等待排查手册",
                artifacts.get(1), "数据库超时排查手册",
                artifacts.get(2), "订单服务连接观测手册");
        var pgvector = new PgvectorKnowledgeSearchAdapter(
                dataSource, (artifactId, location) -> textByArtifact.get(artifactId.value()));
        List<SearchAudit> audits = new ArrayList<>();
        ProviderIdentity embeddingIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-small-zh-v1.5", "7999e1d3359715c523056ef9478215996d62a620");
        ProviderIdentity rerankIdentity = new ProviderIdentity(
                "infinity", "BAAI/bge-reranker-v2-m3", "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e");
        var service = new KnowledgeSearchService(
                pgvector,
                request -> new ProviderResult<>(List.of(new float[]{1, 0, 0}),
                        new ProviderUsage(1, 0, 0L), null),
                pgvector,
                request -> new ProviderResult<>(List.of(
                        new RankedDocument(chunks.get(0).toString(), 0, .99, 1, rerankIdentity),
                        new RankedDocument(chunks.get(1).toString(), 1, .88, 2, rerankIdentity),
                        new RankedDocument(chunks.get(2).toString(), 2, .77, 3, rerankIdentity)),
                        new ProviderUsage(3, 0, 0L), null),
                new PostgresKnowledgeReferenceRepository(dataSource), audits::add);
        var response = service.search(new SearchRequest(
                fixture.runId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, 1,
                new KnowledgeSnapshot(collectionId, knowledgeRevisionId, fixture.revisionId(),
                        3, "COSINE", embeddingIdentity, rerankIdentity),
                "根据连接等待与超时现场信号检索排障资料，并核对历史相似案例",
                20, 5,
                Map.of("language", "zh", "service", "order-service", "documentType", "playbook"),
                Set.of("phase8-empty-outcome-reader"), Instant.now().plusSeconds(60)));

        int historyResultCount = scalarInt("""
                SELECT count(*)
                FROM opspilot.rca_report report
                JOIN opspilot.incident_run historical_run ON historical_run.run_id=report.run_id
                JOIN opspilot.incident historical_incident
                  ON historical_incident.incident_id=historical_run.incident_id
                JOIN opspilot.incident_run current_run ON current_run.run_id=?
                JOIN opspilot.incident current_incident
                  ON current_incident.incident_id=current_run.incident_id
                WHERE historical_run.run_id<>current_run.run_id
                  AND historical_run.status='COMPLETED'
                  AND historical_incident.target_system_id=current_incident.target_system_id
                """, fixture.runId());

        assertNull(response.failure());
        assertEquals(Outcome.MATCH, response.value().outcome());
        assertEquals(3, response.value().references().size());
        assertEquals(0, historyResultCount);
        assertEquals(1, audits.size());
        assertEquals(3, audits.getFirst().candidateCount());
        assertEquals(3, audits.getFirst().rerankCount());
        assertEquals(3, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_reference WHERE run_id=?", fixture.runId()));
        assertEquals(2, scalarInt(
                "SELECT count(*) FROM opspilot.evidence WHERE run_id=?", fixture.runId()));
    }

    @BeforeAll
    static void migrateDatabase() throws Exception {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("phase3_knowledge")
                .withUsername("postgres")
                .withPassword("phase3-test-only");
        postgres.start();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(resource("/db/bootstrap/roles.sql"));
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        var source = new PGSimpleDataSource();
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
    void exactRetrievalEnforcesRevisionVectorAndOperatorContracts() throws Exception {
        Fixture fixture = fixture("exact");
        var versions = new KnowledgeVersionRepository(dataSource);
        var vectors = new PgvectorKnowledgeRepository(dataSource);
        UUID version = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        versions.createDocument(fixture.documentId(), fixture.collectionId(), "doc-exact");
        versions.createVersion(version, fixture.documentId(), 1, HASH_A, artifact(fixture.runId()), 2,
                Instant.parse("2026-10-20T00:00:00Z"));
        versions.appendChunk(first, version, 0, HASH_A, artifact(fixture.runId()), metadata("java"));
        versions.appendChunk(second, version, 1, HASH_B, artifact(fixture.runId()), metadata("java"));
        vectors.insertEmbedding(first, fixture.revisionId(), 3, COSINE, new float[]{1, 0, 0}, HASH_A);
        vectors.insertEmbedding(second, fixture.revisionId(), 3, COSINE, new float[]{0, 1, 0}, HASH_B);
        UUID job = UUID.randomUUID();
        versions.createJob(job, version, fixture.revisionId());
        versions.recordCheckpoint(job, 2, 2, "COMPLETE", null);
        versions.activateVersion(version, fixture.revisionId());

        var hits = vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, 10, Map.of("language", "java")));
        assertEquals(2, hits.size());
        assertEquals(first, hits.getFirst().chunkId());
        assertTrue(hits.getFirst().distance() < hits.getLast().distance());

        assertEquals("VECTOR_NON_FINITE", assertThrows(
                PgvectorKnowledgeRepository.VectorContractException.class,
                () -> vectors.insertEmbedding(UUID.randomUUID(), fixture.revisionId(), 3, COSINE,
                        new float[]{Float.NaN, 0, 0}, HASH_A)).getMessage());
        assertEquals("VECTOR_ZERO_NORM", assertThrows(
                PgvectorKnowledgeRepository.VectorContractException.class,
                () -> vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                        fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                        new float[]{0, 0, 0}, 1, Map.of()))).getMessage());
        assertEquals("VECTOR_FILTER_NOT_ALLOWED", assertThrows(IllegalArgumentException.class,
                () -> vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                        fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                        new float[]{1, 0, 0}, 1, Map.of("x' OR true --", "java")))).getMessage());

        UUID otherRevision = modelRevision(fixture.profileId(), "other-" + UUID.randomUUID());
        assertEquals("VECTOR_QUERY_REVISION_MISMATCH", assertThrows(
                PgvectorKnowledgeRepository.VectorContractException.class,
                () -> vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                        fixture.collectionId(), otherRevision, 3, COSINE,
                        new float[]{1, 0, 0}, 1, Map.of()))).getMessage());
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO opspilot.knowledge_embedding "
                        + "(chunk_id, model_revision_id, embedding_dimension, embedding, content_sha256) "
                        + "VALUES (?, ?, 3, '[0,0,0]'::vector, ?)",
                UUID.randomUUID(), fixture.revisionId(), HASH_A));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE opspilot.model_revision SET distance_metric = 'L2' WHERE model_revision_id = ?",
                fixture.revisionId()));

        assertEquals(0, scalarInt("SELECT count(*) FROM pg_indexes WHERE indexdef ~* '(hnsw|ivfflat)'"));
        assertTrue(scalarText("EXPLAIN SELECT * FROM opspilot.knowledge_chunk "
                + "WHERE document_version_id = '" + version + "' AND searchable AND deleted_at IS NULL")
                .contains("knowledge_chunk"));
    }

    @Test
    void checkpointsReuseAndAtomicActivationRemainRecoverable() throws Exception {
        Fixture fixture = fixture("switch");
        var versions = new KnowledgeVersionRepository(dataSource);
        var vectors = new PgvectorKnowledgeRepository(dataSource);
        UUID version1 = UUID.randomUUID();
        UUID version2 = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        versions.createDocument(fixture.documentId(), fixture.collectionId(), "doc-switch");
        versions.createVersion(version1, fixture.documentId(), 1, HASH_A, artifact(fixture.runId()), 1, null);
        versions.appendChunk(chunk1, version1, 0, HASH_A, artifact(fixture.runId()), metadata("java"));
        vectors.insertEmbedding(chunk1, fixture.revisionId(), 3, COSINE, new float[]{1, 0, 0}, HASH_A);
        UUID job1 = UUID.randomUUID();
        versions.createJob(job1, version1, fixture.revisionId());
        versions.recordCheckpoint(job1, 1, 1, "COMPLETE", null);
        assertEquals(1, versions.loadJob(job1).orElseThrow().checkpointOrdinal());
        versions.activateVersion(version1, fixture.revisionId());

        versions.createVersion(version2, fixture.documentId(), 2, HASH_A, artifact(fixture.runId()), 1, null);
        versions.appendChunk(chunk2, version2, 0, HASH_A, artifact(fixture.runId()), metadata("java"));
        versions.reuseEmbedding(chunk1, chunk2, fixture.revisionId(), fixture.revisionId());
        UUID otherRevision = modelRevision(fixture.profileId(), "switch-other-" + UUID.randomUUID());
        assertEquals("CROSS_REVISION_VECTOR_REUSE_FORBIDDEN", assertThrows(
                KnowledgeVersionRepository.KnowledgePersistenceException.class,
                () -> versions.reuseEmbedding(chunk1, chunk2, fixture.revisionId(), otherRevision)).getMessage());
        UUID job2 = UUID.randomUUID();
        versions.createJob(job2, version2, fixture.revisionId());
        versions.recordChunkFailure(job2, chunk2, 1, "TRANSIENT", "retryable");
        versions.recordCheckpoint(job2, 0, 0, "PARTIAL", "retryable");
        assertEquals(1, versions.loadJob(job2).orElseThrow().failedChunks());
        assertEquals("KNOWLEDGE_COVERAGE_INCOMPLETE", assertThrows(
                KnowledgeVersionRepository.KnowledgePersistenceException.class,
                () -> versions.activateVersion(version2, fixture.revisionId())).getMessage());
        versions.recordCheckpoint(job2, 1, 1, "COMPLETE", null);

        assertThrows(InjectedFailure.class, () -> versions.activateVersion(
                version2, fixture.revisionId(), point -> {
                    if (point == KnowledgeVersionRepository.ActivationPoint.AFTER_OLD_REVISION_DISABLED) {
                        throw new InjectedFailure();
                    }
                }));
        assertEquals(version1, scalarUuid(
                "SELECT active_version_id FROM opspilot.knowledge_document WHERE document_id = ?",
                fixture.documentId()));
        assertTrue(scalarBoolean("SELECT searchable FROM opspilot.knowledge_chunk WHERE chunk_id = ?", chunk1));

        versions.activateVersion(version2, fixture.revisionId());
        assertEquals(version2, scalarUuid(
                "SELECT active_version_id FROM opspilot.knowledge_document WHERE document_id = ?",
                fixture.documentId()));
        versions.activateVersion(version1, fixture.revisionId());
        assertEquals(chunk1, vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, 1, Map.of())).getFirst().chunkId());
        versions.markDeleted(fixture.documentId(), Instant.parse("2026-07-22T00:00:00Z"));
        assertTrue(vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, 1, Map.of())).isEmpty());
    }

    @Test
    void embeddingBatchVectorsCoverageAndCheckpointCommitAtomically() throws Exception {
        Fixture fixture = fixture("atomic-batch");
        var versions = new KnowledgeVersionRepository(dataSource);
        var batches = new PostgresEmbeddingBatchCommitRepository(dataSource);
        UUID version = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        versions.createDocument(fixture.documentId(), fixture.collectionId(), "doc-atomic-batch");
        versions.createVersion(version, fixture.documentId(), 1, HASH_A, artifact(fixture.runId()), 2, null);
        versions.appendChunk(first, version, 0, HASH_A, artifact(fixture.runId()), metadata("java"));
        versions.appendChunk(second, version, 1, HASH_B, artifact(fixture.runId()), metadata("java"));
        versions.createJob(job, version, fixture.revisionId());

        EmbeddingBatchCommit invalid = new EmbeddingBatchCommit(job, fixture.revisionId(), 1,
                2, 2, 3, "COSINE", List.of(
                new EmbeddingVectorWrite(first, HASH_A, new float[]{1, 0, 0}),
                new EmbeddingVectorWrite(second, HASH_A, new float[]{0, 1, 0})));
        assertEquals("EMBEDDING_VECTOR_IDENTITY_MISMATCH", assertThrows(
                PostgresEmbeddingBatchCommitRepository.EmbeddingBatchPersistenceException.class,
                () -> batches.commit(invalid)).getMessage());
        assertEquals(0, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_embedding WHERE model_revision_id = ?",
                fixture.revisionId()));
        assertEquals(0, batches.load(job).orElseThrow().checkpointOrdinal());
        assertEquals(0, batches.load(job).orElseThrow().completedChunks());

        batches.commit(new EmbeddingBatchCommit(job, fixture.revisionId(), 1,
                2, 2, 3, "COSINE", List.of(
                new EmbeddingVectorWrite(first, HASH_A, new float[]{1, 0, 0}),
                new EmbeddingVectorWrite(second, HASH_B, new float[]{0, 1, 0}))));

        assertEquals(2, scalarInt(
                "SELECT count(*) FROM opspilot.knowledge_embedding WHERE model_revision_id = ?",
                fixture.revisionId()));
        var completed = batches.load(job).orElseThrow();
        assertEquals(1, completed.checkpointOrdinal());
        assertEquals(2, completed.completedChunks());
        assertEquals("COMPLETED", completed.status());
    }

    @Test
    void runSnapshotsAtomicRevisionSwitchRollbackAndReferenceResolutionStayIsolated() throws Exception {
        Fixture fixture = fixture("knowledge-revision");
        var versions = new KnowledgeVersionRepository(dataSource);
        var revisions = new KnowledgeRevisionRepository(dataSource);
        var vectors = new PgvectorKnowledgeRepository(dataSource);
        var references = new PostgresKnowledgeReferenceRepository(dataSource);
        UUID oldRevision = knowledgeRevision(fixture.collectionId(), fixture.revisionId(), "READY");
        UUID newRevision = knowledgeRevision(fixture.collectionId(), fixture.revisionId(), "READY");
        UUID oldVersion = UUID.randomUUID();
        UUID newVersion = UUID.randomUUID();
        UUID oldChunk = UUID.randomUUID();
        UUID newChunk = UUID.randomUUID();
        UUID oldDocument = fixture.documentId();
        UUID newDocument = UUID.randomUUID();

        versions.createDocument(oldDocument, fixture.collectionId(), "old-runbook");
        versions.createVersion(oldVersion, oldDocument, 1, HASH_A, artifact(fixture.runId()), 1, null);
        versions.appendChunk(oldChunk, oldVersion, 0, HASH_A, artifact(fixture.runId()),
                "{\"schemaVersion\":\"1.0.0\",\"service\":\"payment\",\"tag\":\"runbook\",\"relation\":\"incident:db\"}");
        execute("UPDATE opspilot.knowledge_document_version SET knowledge_revision_id = ?, "
                        + "acl_json = '{\"principals\":[\"role:knowledge\"]}'::jsonb WHERE document_version_id = ?",
                oldRevision, oldVersion);
        vectors.insertEmbedding(oldChunk, fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, HASH_A);
        UUID oldJob = UUID.randomUUID();
        versions.createJob(oldJob, oldVersion, fixture.revisionId());
        versions.recordCheckpoint(oldJob, 1, 1, "COMPLETE", null);
        versions.activateVersion(oldVersion, fixture.revisionId());
        revisions.activate(fixture.collectionId(), oldRevision);
        var oldSnapshot = revisions.resolveRunSnapshot(fixture.runId(), fixture.collectionId());
        assertEquals(oldRevision, oldSnapshot.knowledgeRevisionId());

        versions.createDocument(newDocument, fixture.collectionId(), "new-runbook");
        versions.createVersion(newVersion, newDocument, 1, HASH_B, artifact(fixture.runId()), 1, null);
        versions.appendChunk(newChunk, newVersion, 0, HASH_B, artifact(fixture.runId()),
                "{\"schemaVersion\":\"1.0.0\",\"service\":\"payment\",\"tag\":\"runbook\",\"relation\":\"incident:db\"}");
        execute("UPDATE opspilot.knowledge_document_version SET knowledge_revision_id = ?, "
                        + "acl_json = '{\"principals\":[\"role:knowledge\"]}'::jsonb WHERE document_version_id = ?",
                newRevision, newVersion);
        vectors.insertEmbedding(newChunk, fixture.revisionId(), 3, COSINE,
                new float[]{0, 1, 0}, HASH_B);
        UUID newJob = UUID.randomUUID();
        versions.createJob(newJob, newVersion, fixture.revisionId());
        versions.recordCheckpoint(newJob, 1, 1, "COMPLETE", null);
        versions.activateVersion(newVersion, fixture.revisionId());

        assertThrows(InjectedFailure.class, () -> revisions.activate(
                fixture.collectionId(), newRevision, point -> {
                    if (point == KnowledgeRevisionRepository.ActivationPoint.AFTER_OLD_REVISION_DISABLED) {
                        throw new InjectedFailure();
                    }
                }));
        assertEquals(oldRevision, scalarUuid(
                "SELECT active_knowledge_revision_id FROM opspilot.knowledge_collection WHERE collection_id = ?",
                fixture.collectionId()));
        revisions.activate(fixture.collectionId(), newRevision);

        assertEquals(oldRevision, revisions.resolveRunSnapshot(
                fixture.runId(), fixture.collectionId()).knowledgeRevisionId());
        UUID newRun = createRun("new-snapshot");
        assertEquals(newRevision, revisions.resolveRunSnapshot(
                newRun, fixture.collectionId()).knowledgeRevisionId());

        Map<String, String> filters = Map.of(
                "service", "payment", "tag", "runbook", "relation", "incident:db");
        var oldHits = vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, 10, filters, oldRevision, Set.of("role:knowledge")));
        var newHits = vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{0, 1, 0}, 10, filters, newRevision, Set.of("role:knowledge")));
        assertEquals(List.of(oldChunk), oldHits.stream().map(
                PgvectorKnowledgeRepository.SearchHit::chunkId).toList());
        assertEquals(List.of(newChunk), newHits.stream().map(
                PgvectorKnowledgeRepository.SearchHit::chunkId).toList());
        assertTrue(vectors.exactTopK(new PgvectorKnowledgeRepository.SearchRequest(
                fixture.collectionId(), fixture.revisionId(), 3, COSINE,
                new float[]{1, 0, 0}, 10, filters, oldRevision, Set.of("role:other"))).isEmpty());

        UUID referenceId = UUID.randomUUID();
        KnowledgeReference reference = new KnowledgeReference(referenceId, fixture.collectionId(),
                oldDocument, oldVersion, oldChunk, oldRevision, fixture.revisionId(),
                new ArtifactId(oldHits.getFirst().contentArtifactId()), "legacy", HASH_A, "c".repeat(64),
                oldHits.getFirst().distance(), 8.5, 1,
                new ProviderIdentity("infinity", "rerank", "locked"));
        references.persist(fixture.runId(), reference);
        assertEquals(reference, references.resolve(referenceId, fixture.runId(),
                Set.of("role:knowledge")).orElseThrow());
        assertTrue(references.resolve(referenceId, fixture.runId(), Set.of("role:other")).isEmpty());

        revisions.rollback(fixture.collectionId(), oldRevision, true, true);
        assertEquals(oldRevision, scalarUuid(
                "SELECT active_knowledge_revision_id FROM opspilot.knowledge_collection WHERE collection_id = ?",
                fixture.collectionId()));
        assertFalse(revisions.markCleaned(oldRevision));
    }

    private static Fixture fixture(String suffix) throws SQLException {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String target = "target-" + suffix + '-' + UUID.randomUUID();
        execute("INSERT INTO opspilot.target_system (target_system_id, display_name) VALUES (?, ?)", target, target);
        execute("INSERT INTO opspilot.incident (incident_id, target_system_id, status) VALUES (?, ?, 'OPEN')",
                incidentId, target);
        execute("INSERT INTO opspilot.incident_run (run_id, incident_id, status) VALUES (?, ?, 'CREATED')",
                runId, incidentId);
        execute("INSERT INTO opspilot.model_provider (provider_id, provider_key, display_name) VALUES (?, ?, ?)",
                providerId, "provider-" + providerId, "test");
        execute("INSERT INTO opspilot.model_profile (profile_id, provider_id, profile_key, purpose, config_json) "
                        + "VALUES (?, ?, ?, 'EMBEDDING', '{\"schemaVersion\":\"1.0.0\"}'::jsonb)",
                profileId, providerId, "profile-" + profileId);
        execute("INSERT INTO opspilot.model_revision "
                        + "(model_revision_id, profile_id, revision_key, embedding_dimension, distance_metric, normalization) "
                        + "VALUES (?, ?, ?, 3, 'COSINE', 'L2')",
                revisionId, profileId, "revision-" + revisionId);
        execute("INSERT INTO opspilot.knowledge_collection (collection_id, collection_key) VALUES (?, ?)",
                collectionId, "collection-" + collectionId);
        return new Fixture(runId, profileId, revisionId, collectionId, documentId);
    }

    private static UUID modelRevision(UUID profileId, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO opspilot.model_revision "
                        + "(model_revision_id, profile_id, revision_key, embedding_dimension, distance_metric, normalization) "
                        + "VALUES (?, ?, ?, 3, 'COSINE', 'L2')", id, profileId, key);
        return id;
    }

    private static UUID artifact(UUID runId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO opspilot.artifact "
                        + "(artifact_id, run_id, uri, sha256, media_type, access_level, object_key, size_bytes) "
                        + "VALUES (?, ?, ?, ?, 'text/plain', 'INTERNAL', ?, 1)",
                id, runId, "artifact://" + id, HASH_A, id.toString());
        return id;
    }

    private static String metadata(String language) {
        return "{\"schemaVersion\":\"1.0.0\",\"language\":\"" + language + "\"}";
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                if (values[index] instanceof Instant instant) {
                    statement.setTimestamp(index + 1, Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, values[index]);
                }
            }
            statement.executeUpdate();
        }
    }

    private static int scalarInt(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
            }
        }
    }

    private static UUID knowledgeRevision(UUID collectionId, UUID modelRevisionId, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO opspilot.knowledge_revision "
                        + "(knowledge_revision_id, collection_id, status, normalization_version, "
                        + "chunk_strategy_version, model_revision_id, embedding_dimension, coverage_status, "
                        + "expected_chunk_count, completed_chunk_count) "
                        + "VALUES (?, ?, ?, 'norm-v1', 'chunk-v1', ?, 3, 'COMPLETE', 1, 1)",
                id, collectionId, status, modelRevisionId);
        return id;
    }

    private static UUID createRun(String suffix) throws SQLException {
        String target = "target-" + suffix + '-' + UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        execute("INSERT INTO opspilot.target_system (target_system_id, display_name) VALUES (?, ?)", target, target);
        execute("INSERT INTO opspilot.incident (incident_id, target_system_id, status) VALUES (?, ?, 'OPEN')",
                incident, target);
        execute("INSERT INTO opspilot.incident_run (run_id, incident_id, status) VALUES (?, ?, 'CREATED')",
                run, incident);
        return run;
    }

    private static String scalarText(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            StringBuilder text = new StringBuilder();
            while (result.next()) {
                text.append(result.getString(1)).append('\n');
            }
            return text.toString();
        }
    }

    private static UUID scalarUuid(String sql, Object value) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static boolean scalarBoolean(String sql, Object value) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String resource(String name) throws IOException {
        try (var stream = PgvectorKnowledgeRepositoryTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Fixture(
            UUID runId, UUID profileId, UUID revisionId, UUID collectionId, UUID documentId) { }

    private static final class InjectedFailure extends RuntimeException { }
}
