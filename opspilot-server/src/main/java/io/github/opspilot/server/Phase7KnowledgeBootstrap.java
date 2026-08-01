package io.github.opspilot.server;

import io.github.opspilot.adapters.knowledge.pgvector.KnowledgeRevisionRepository;
import io.github.opspilot.adapters.knowledge.pgvector.PgvectorKnowledgeRepository;
import io.github.opspilot.adapters.knowledge.pgvector.PgvectorKnowledgeRepository.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingAdapter;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Idempotently builds the frozen Phase-7 playbook collection from real Infinity embeddings. */
final class Phase7KnowledgeBootstrap {
    private static final UUID PROVIDER_ID = id("phase7:provider:infinity");
    private static final UUID PROFILE_ID = id("phase7:profile:embedding-bge-small-zh-v1.5");
    private static final UUID MODEL_REVISION_ID = id("phase7:model:embedding-bge-small-zh-v1.5:7999e1d");
    private static final UUID COLLECTION_ID = id("phase7:knowledge:incident-playbooks");
    private static final UUID KNOWLEDGE_REVISION_ID = id("phase7:knowledge:incident-playbooks:1.0.0");
    private static final UUID BOOTSTRAP_INCIDENT_ID = id("phase7:knowledge:bootstrap-incident");
    private static final UUID BOOTSTRAP_RUN_ID = id("phase7:knowledge:bootstrap-run");
    private static final List<Playbook> PLAYBOOKS = List.of(
            new Playbook("dependency-latency-inventory",
                    "库存依赖延迟：检查 order 到 inventory 的 client span、网关延迟和下游资源饱和度。"
                            + "若 client span p95 超过 2500ms 且 inventory CPU 与数据库未饱和，优先判定依赖延迟；"
                            + "先移除 Toxiproxy latency toxic，再验证 60 秒恢复窗口。"),
            new Playbook("database-pool-exhausted-order",
                    "订单数据库连接池耗尽：同时检查 Hikari active、pending、maximumPoolSize 与 connection timeout。"
                            + "active 持续等于 max、pending 大于零并出现真实获取连接超时，才支持连接池耗尽；"
                            + "释放受控长事务后验证 pending 清零，禁止停止共享 PostgreSQL。"),
            new Playbook("service-instance-stopped-inventory",
                    "库存实例停止：同时检查 readiness 不可达、Prometheus target DOWN、连接拒绝或 503。"
                            + "仅慢响应或瞬时健康抖动不足以判定实例停止；恢复必须启动原容器配置并验证卷与数据未改变。"));

    private final DataSource dataSource;
    private final Map<String, String> environment;

    Phase7KnowledgeBootstrap(DataSource dataSource, Map<String, String> environment) {
        this.dataSource = dataSource;
        this.environment = Map.copyOf(environment);
    }

    synchronized void ensureReady() throws Exception {
        if (active()) return;
        createMetadata();
        List<float[]> vectors = embed();
        var repository = new PgvectorKnowledgeRepository(dataSource);
        for (int index = 0; index < PLAYBOOKS.size(); index++) {
            Playbook playbook = PLAYBOOKS.get(index);
            UUID chunkId = id("phase7:chunk:" + playbook.key());
            if (!embeddingExists(chunkId)) {
                repository.insertEmbedding(chunkId, MODEL_REVISION_ID, 512,
                        DistanceMetric.COSINE, vectors.get(index), sha256(playbook.text()));
            }
        }
        var revisions = new KnowledgeRevisionRepository(dataSource);
        String status = revisionStatus();
        if ("BUILDING".equals(status)) {
            revisions.markReady(KNOWLEDGE_REVISION_ID, PLAYBOOKS.size(), PLAYBOOKS.size());
            status = "READY";
        }
        if ("READY".equals(status) || "RETAINED".equals(status)) {
            revisions.activate(COLLECTION_ID, KNOWLEDGE_REVISION_ID);
        }
    }

    private List<float[]> embed() {
        String model = required("EMBEDDING_MODEL_ID");
        String revision = required("EMBEDDING_MODEL_REVISION");
        var adapter = new InfinityEmbeddingAdapter(new InfinityEmbeddingConfiguration(
                "infinity", URI.create(required("EMBEDDING_BASE_URL")), model, revision, 512,
                InfinityEmbeddingConfiguration.Normalization.L2_UNIT,
                InfinityEmbeddingConfiguration.DistanceMetric.COSINE, 16, 16, 8192),
                text -> Math.max(1, (text.length() + 1) / 2));
        var result = adapter.embed(new EmbeddingRequest(
                new ProviderIdentity("infinity", model, revision),
                Instant.now().plusSeconds(180), PLAYBOOKS.stream().map(Playbook::text).toList()));
        if (result.failure() != null) throw new IllegalStateException(result.failure().errorCode());
        return result.value();
    }

    private void createMetadata() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                update(connection, "INSERT INTO opspilot.target_system "
                        + "(target_system_id,display_name) VALUES ('phase7-knowledge-bootstrap','Phase 7 knowledge bootstrap') "
                        + "ON CONFLICT DO NOTHING");
                update(connection, "INSERT INTO opspilot.incident (incident_id,target_system_id,status) "
                        + "VALUES (?,'phase7-knowledge-bootstrap','RESOLVED') ON CONFLICT DO NOTHING",
                        BOOTSTRAP_INCIDENT_ID);
                update(connection, "INSERT INTO opspilot.incident_run (run_id,incident_id,status,ended_at) "
                        + "VALUES (?,?,'COMPLETED',now()) ON CONFLICT DO NOTHING",
                        BOOTSTRAP_RUN_ID, BOOTSTRAP_INCIDENT_ID);
                update(connection, "INSERT INTO opspilot.model_provider "
                        + "(provider_id,provider_key,display_name) VALUES (?,'infinity','Infinity') "
                        + "ON CONFLICT (provider_key) DO NOTHING", PROVIDER_ID);
                update(connection, "INSERT INTO opspilot.model_profile "
                        + "(profile_id,provider_id,profile_key,purpose,config_json) "
                        + "VALUES (?,?,'embedding-bge-small-zh-v1.5','EMBEDDING',"
                        + "'{\"schemaVersion\":\"1.0.0\"}'::jsonb) ON CONFLICT DO NOTHING",
                        PROFILE_ID, PROVIDER_ID);
                update(connection, "INSERT INTO opspilot.model_revision "
                        + "(model_revision_id,profile_id,revision_key,embedding_dimension,distance_metric,normalization,active) "
                        + "VALUES (?,?,?,512,'COSINE','L2',true) ON CONFLICT DO NOTHING",
                        MODEL_REVISION_ID, PROFILE_ID, required("EMBEDDING_MODEL_REVISION"));
                update(connection, "INSERT INTO opspilot.knowledge_collection "
                        + "(collection_id,collection_key,active_model_revision_id) "
                        + "VALUES (?,'phase7-incident-playbooks',?) ON CONFLICT DO NOTHING",
                        COLLECTION_ID, MODEL_REVISION_ID);
                update(connection, "INSERT INTO opspilot.knowledge_revision "
                        + "(knowledge_revision_id,collection_id,status,normalization_version,chunk_strategy_version,"
                        + "model_revision_id,embedding_dimension,coverage_status,expected_chunk_count,completed_chunk_count,searchable) "
                        + "VALUES (?,?,'BUILDING','nfkc-v1','whole-playbook-v1',?,512,'PENDING',?,0,false) "
                        + "ON CONFLICT DO NOTHING", KNOWLEDGE_REVISION_ID, COLLECTION_ID,
                        MODEL_REVISION_ID, PLAYBOOKS.size());
                for (Playbook playbook : PLAYBOOKS) createPlaybook(connection, playbook);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void createPlaybook(java.sql.Connection connection, Playbook playbook) throws Exception {
        UUID artifactId = id("phase7:artifact:" + playbook.key());
        UUID documentId = id("phase7:document:" + playbook.key());
        UUID versionId = id("phase7:document-version:" + playbook.key());
        UUID chunkId = id("phase7:chunk:" + playbook.key());
        String digest = sha256(playbook.text());
        update(connection, "INSERT INTO opspilot.artifact "
                + "(artifact_id,run_id,uri,sha256,media_type,access_level,object_key,size_bytes) "
                + "VALUES (?,?,?,?,'text/plain','INTERNAL',?,?) "
                + "ON CONFLICT DO NOTHING", artifactId, BOOTSTRAP_RUN_ID,
                "artifact://phase7-playbook/" + playbook.key(), digest,
                "phase7-playbook/" + playbook.key() + ".txt",
                playbook.text().getBytes(StandardCharsets.UTF_8).length);
        update(connection, "INSERT INTO opspilot.knowledge_document "
                + "(document_id,collection_id,external_key,status,metadata_json) "
                + "VALUES (?,? ,?,'ACTIVE','{\"schemaVersion\":\"1.0.0\"}'::jsonb) ON CONFLICT DO NOTHING",
                documentId, COLLECTION_ID, playbook.key());
        update(connection, "INSERT INTO opspilot.knowledge_document_version "
                + "(document_version_id,document_id,version_number,status,content_sha256,source_artifact_id,"
                + "coverage_status,expected_chunk_count,completed_chunk_count,knowledge_revision_id,"
                + "normalization_version,chunk_strategy_version,acl_json) "
                + "VALUES (?,?,1,'ACTIVE',?,?,'COMPLETE',1,1,?,'nfkc-v1','whole-playbook-v1',"
                + "'{\"principals\":[\"svc:knowledge-agent\"]}'::jsonb) ON CONFLICT DO NOTHING",
                versionId, documentId, digest, artifactId, KNOWLEDGE_REVISION_ID);
        update(connection, "UPDATE opspilot.knowledge_document SET active_version_id=? "
                + "WHERE document_id=? AND active_version_id IS NULL", versionId, documentId);
        update(connection, "INSERT INTO opspilot.knowledge_chunk "
                + "(chunk_id,document_version_id,ordinal,content_sha256,content_artifact_id,searchable,metadata_json,source_location,acl_json) "
                + "VALUES (?,?,0,?,?,true,jsonb_build_object('schemaVersion','1.0.0','text',?::text),?,"
                + "'{\"principals\":[\"svc:knowledge-agent\"]}'::jsonb) ON CONFLICT DO NOTHING",
                chunkId, versionId, digest, artifactId, playbook.text(), "playbook://" + playbook.key());
    }

    private boolean active() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                       SELECT 1 FROM opspilot.knowledge_collection collection
                       JOIN opspilot.knowledge_revision revision
                         ON revision.knowledge_revision_id=collection.active_knowledge_revision_id
                       WHERE collection.collection_id=? AND revision.status='ACTIVE'
                         AND revision.searchable AND revision.completed_chunk_count=?
                     )
                     """)) {
            statement.setObject(1, COLLECTION_ID);
            statement.setInt(2, PLAYBOOKS.size());
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private boolean embeddingExists(UUID chunkId) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM opspilot.knowledge_embedding WHERE chunk_id=? AND model_revision_id=?)")) {
            statement.setObject(1, chunkId);
            statement.setObject(2, MODEL_REVISION_ID);
            try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    private String revisionStatus() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT status FROM opspilot.knowledge_revision WHERE knowledge_revision_id=?")) {
            statement.setObject(1, KNOWLEDGE_REVISION_ID);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("KNOWLEDGE_REVISION_MISSING");
                return result.getString(1);
            }
        }
    }

    private static void update(java.sql.Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private String required(String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + "_MISSING");
        return value;
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record Playbook(String key, String text) { }
}
