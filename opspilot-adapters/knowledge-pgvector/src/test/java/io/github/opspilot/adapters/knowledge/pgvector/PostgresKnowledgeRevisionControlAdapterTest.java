package io.github.opspilot.adapters.knowledge.pgvector;

import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedChunk;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedRevision;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PostgresKnowledgeRevisionControlAdapterTest {
    private static final String IMAGE = "pgvector/pgvector:pg16";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final UUID COLLECTION = id("control-collection");
    private static final UUID MODEL_REVISION = id("control-model-revision");
    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        postgres = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("knowledge_control")
                .withUsername("postgres")
                .withPassword("knowledge-control-test-only");
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
        seedCollection();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void preparesActivatesAndRestoresImmutableRevisionThroughPublicPort() {
        var adapter = new PostgresKnowledgeRevisionControlAdapter(dataSource);
        UUID original = id("original-revision");
        UUID target = id("target-revision");

        assertEquals("READY", adapter.prepare(revision(original, "original", "a".repeat(64))).status());
        var originalReceipt = adapter.activate(
                "activate-original", "fault-lab:phase8", COLLECTION, original, null,
                NOW.plusSeconds(900), NOW);
        assertEquals(original, adapter.findActiveRevision(COLLECTION).orElseThrow());

        assertEquals("READY", adapter.prepare(revision(target, "target", "b".repeat(64))).status());
        var receipt = adapter.activate(
                "activate-target-01", "fault-lab:phase8", COLLECTION, target, original,
                NOW.plusSeconds(600), NOW.plusSeconds(10));

        assertEquals(original, receipt.previousRevisionId());
        assertEquals(target, adapter.findActiveRevision(COLLECTION).orElseThrow());
        assertNotEquals(originalReceipt.receiptId(), receipt.receiptId());
        assertEquals(receipt, adapter.findReceipt(receipt.receiptId()).orElseThrow());

        var restored = adapter.restore(
                "restore-target-001", "fault-lab:phase8", receipt, NOW.plusSeconds(20));
        assertEquals(NOW.plusSeconds(20), restored.restoredAt());
        assertEquals(original, adapter.findActiveRevision(COLLECTION).orElseThrow());
    }

    @Test
    void prepareIsIdempotentAndActivationRejectsStaleExpectedActiveRevision() {
        var adapter = new PostgresKnowledgeRevisionControlAdapter(dataSource);
        UUID revision = id("idempotent-revision");
        PreparedRevision command = revision(revision, "idempotent", "c".repeat(64));

        assertEquals(adapter.prepare(command), adapter.prepare(command));
        var failure = assertThrows(PostgresKnowledgeRevisionControlAdapter.ControlPersistenceException.class,
                () -> adapter.activate(
                        "activate-stale-01", "fault-lab:phase8", COLLECTION, revision,
                        id("stale-active"), NOW.plusSeconds(600), NOW.plusSeconds(30)));
        assertEquals("KNOWLEDGE_CONTROL_ACTIVE_REVISION_CONFLICT", failure.getMessage());
    }

    private static PreparedRevision revision(UUID revisionId, String key, String digest) {
        UUID chunkId = id("chunk-" + key);
        return new PreparedRevision(
                "prepare-" + key + "-01", "fault-lab:phase8", COLLECTION, revisionId,
                "phase8/" + key + "/1.0.0", digest, MODEL_REVISION, "infinity-test-revision",
                3, "COSINE", NOW.plusSeconds(900), List.of(new PreparedChunk(
                chunkId, "document-" + key, "controlled content " + key,
                sha256("controlled content " + key), Map.of("scenario", key),
                List.copyOf(Set.of("knowledge-agent")), new float[]{1, 0, 0})));
    }

    private static void seedCollection() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO opspilot.model_provider(provider_id,provider_key,display_name) "
                    + "VALUES ('" + id("provider") + "','infinity-test','Infinity Test')");
            statement.execute("INSERT INTO opspilot.model_profile(profile_id,provider_id,profile_key,purpose,config_json) "
                    + "VALUES ('" + id("profile") + "','" + id("provider")
                    + "','embedding-test','EMBEDDING','{\"schemaVersion\":\"1.0.0\"}')");
            statement.execute("INSERT INTO opspilot.model_revision(model_revision_id,profile_id,revision_key,"
                    + "embedding_dimension,distance_metric,normalization,active) VALUES ('" + MODEL_REVISION
                    + "','" + id("profile") + "','infinity-test-revision',3,'COSINE','L2',true)");
            statement.execute("INSERT INTO opspilot.knowledge_collection(collection_id,collection_key,active_model_revision_id) "
                    + "VALUES ('" + COLLECTION + "','phase8-control-test','" + MODEL_REVISION + "')");
        }
    }

    private static String resource(String path) throws Exception {
        try (var stream = PostgresKnowledgeRevisionControlAdapterTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("knowledge-control:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
