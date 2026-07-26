package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.postgresql.util.PSQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(OrderAnnotation.class)
final class PostgresPhase0MigrationTest {
    private static final String IMAGE = "pgvector/pgvector@sha256:ad2e18408bf447f62092a8a5259e7df10505c5a0360bd1a1853ac8b8b0763da2";
    private static final Set<String> SCHEMAS = Set.of("opspilot", "opspilot_a2a", "sample", "opspilot_eval");
    private static final Set<String> ROLES = Set.of(
            "opspilot_migrator", "opspilot_app_role", "sample_app_role", "fault_lab_role",
            "evaluation_role", "professional_agent_role", "evidence_agent_role", "code_agent_role",
            "knowledge_agent_role", "diagnosis_agent_role", "remediation_agent_role");
    private static final Set<String> PHASE0_TABLES = Set.of(
            "opspilot.incident", "opspilot.incident_run", "opspilot.agent_state",
            "opspilot.artifact", "opspilot.evidence", "opspilot.evaluation_result",
            "opspilot_a2a.task", "opspilot_a2a.task_event", "opspilot_a2a.agent_runtime_state",
            "opspilot_eval.ground_truth");

    private static PostgreSQLContainer postgres;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startPostgresAndCreateRoles() throws Exception {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase0-test-only");
        postgres.start();
        assertEquals(IMAGE, postgres.getDockerImageName());
        try (Connection connection = connection("postgres"); Statement statement = connection.createStatement()) {
            statement.execute(resource("/db/bootstrap/roles.sql"));
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Order(1)
    void emptyDatabaseCreatesLockedPgvectorSchemasAndRolesWithoutH2() throws Exception {
        String database = createDatabase("wp07_t01");
        migrate(database, null);

        try (Connection connection = connection(database)) {
            assertEquals("12", queryString(connection,
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"));
            assertNotNull(queryString(connection, "SELECT extversion FROM pg_extension WHERE extname = 'vector'"));
            assertEquals(SCHEMAS, querySet(connection,
                    "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('opspilot','opspilot_a2a','sample','opspilot_eval')"));
            assertEquals(ROLES, querySet(connection,
                    "SELECT rolname FROM pg_roles WHERE rolname IN ('opspilot_migrator','opspilot_app_role','sample_app_role','fault_lab_role','evaluation_role','professional_agent_role','evidence_agent_role','code_agent_role','knowledge_agent_role','diagnosis_agent_role','remediation_agent_role')"));
            assertTrue(queryBoolean(connection,
                    "SELECT has_schema_privilege('evidence_agent_role', 'opspilot_a2a', 'USAGE')"));
            assertFalse(queryBoolean(connection,
                    "SELECT has_schema_privilege('evidence_agent_role', 'opspilot', 'USAGE')"));
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.h2.Driver"));
    }

    @Test
    @Order(2)
    void phase3MigrationPreservesVerticalSliceWhileExpandingTheFrozenSchema() throws Exception {
        String database = createDatabase("wp07_t02");
        migrate(database, null);
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO opspilot.incident VALUES ('" + incidentId + "','sample-system','OPEN',now())");
            statement.executeUpdate("INSERT INTO opspilot.incident_run (run_id,incident_id,status) VALUES ('" + runId + "','" + incidentId + "','CREATED')");
            statement.executeUpdate("INSERT INTO opspilot.agent_state VALUES ('" + runId + "','1.0','{\"schemaVersion\":\"1.0\",\"version\":1}',1,now())");
            statement.executeUpdate("INSERT INTO opspilot.artifact (artifact_id,run_id,uri,sha256,media_type,access_level,object_key,size_bytes) VALUES ('" + artifactId + "','" + runId + "','artifact://phase0','" + "a".repeat(64) + "','application/json','INTERNAL','" + artifactId + "',0)");
            statement.executeUpdate("INSERT INTO opspilot.evidence (evidence_id,run_id,summary,artifact_id) VALUES ('" + UUID.randomUUID() + "','" + runId + "','phase0 evidence','" + artifactId + "')");
            statement.executeUpdate("INSERT INTO opspilot.evaluation_result (evaluation_id,run_id,metrics_json) VALUES ('" + UUID.randomUUID() + "','" + runId + "','{\"schemaVersion\":\"1.0.0\",\"score\":1}')");
            statement.executeUpdate("INSERT INTO opspilot_a2a.task (task_id,server_agent_id,message_id,request_hash,state,payload_json) VALUES ('task-1','evidence-collector','message-1','hash-1','SUBMITTED','{\"schemaVersion\":\"1.0.0\"}')");
            statement.executeUpdate("INSERT INTO opspilot_a2a.task_event (task_id,server_agent_id,event_type,payload_json) VALUES ('task-1','evidence-collector','SUBMITTED','{\"schemaVersion\":\"1.0.0\"}')");
            statement.executeUpdate("INSERT INTO opspilot_a2a.agent_runtime_state VALUES ('evidence-collector','opspilot-system','evidence-agent:task-1','1.0','{\"schemaVersion\":\"1.0\"}',1,now())");
            statement.executeUpdate("INSERT INTO opspilot_eval.ground_truth (scenario_id,root_cause_code,expected_evidence,expected_actions) VALUES ('scenario-1','ROOT_CAUSE','{\"schemaVersion\":\"1.0.0\",\"items\":[]}','{\"schemaVersion\":\"1.0.0\",\"items\":[]}')");

            assertTrue(queryQualifiedTableSet(connection).containsAll(PHASE0_TABLES));
            assertEquals(10, queryInt(connection, "SELECT count(*) FROM ("
                    + "SELECT incident_id::text FROM opspilot.incident UNION ALL "
                    + "SELECT run_id::text FROM opspilot.incident_run UNION ALL "
                    + "SELECT run_id::text FROM opspilot.agent_state UNION ALL "
                    + "SELECT artifact_id::text FROM opspilot.artifact UNION ALL "
                    + "SELECT evidence_id::text FROM opspilot.evidence UNION ALL "
                    + "SELECT evaluation_id::text FROM opspilot.evaluation_result UNION ALL "
                    + "SELECT task_id FROM opspilot_a2a.task UNION ALL "
                    + "SELECT event_id::text FROM opspilot_a2a.task_event UNION ALL "
                    + "SELECT session_id FROM opspilot_a2a.agent_runtime_state UNION ALL "
                    + "SELECT scenario_id FROM opspilot_eval.ground_truth) phase0_rows"));
        }
    }

    @Test
    @Order(3)
    void concurrentTransactionsMapUniqueConflictAndCommitOnlyOneActiveRun() throws Exception {
        String database = createDatabase("wp07_t03");
        migrate(database, null);
        UUID incidentId = UUID.randomUUID();
        try (Connection connection = connection(database); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO opspilot.incident (incident_id,target_system_id,status) VALUES (?, 'sample-system', 'OPEN')")) {
            statement.setObject(1, incidentId);
            statement.executeUpdate();
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                UUID runId = UUID.randomUUID();
                futures.add(executor.submit(() -> createActiveRun(database, incidentId, runId, ready, start)));
            }
            ready.await();
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());
            assertEquals(1, results.stream().filter(UUID.class::isInstance).count());
            ActiveRunExistsException conflict = (ActiveRunExistsException) results.stream()
                    .filter(ActiveRunExistsException.class::isInstance).findFirst().orElseThrow();
            assertEquals("INCIDENT_ACTIVE_RUN_EXISTS", conflict.getMessage().split(":", 2)[0]);
            assertEquals(results.stream().filter(UUID.class::isInstance).findFirst().orElseThrow(), conflict.existingRunId());
        }
        try (Connection connection = connection(database)) {
            assertEquals(1, queryInt(connection,
                    "SELECT count(*) FROM opspilot.incident_run WHERE incident_id = '" + incidentId
                            + "' AND status NOT IN ('COMPLETED','FAILED','CANCELLED')"));
        }
    }

    @Test
    @Order(4)
    void upgradesPreviousVersionAndRejectsProfessionalAgentDomainAndGroundTruthAccess() throws Exception {
        String upgradeDatabase = createDatabase("wp07_t04_upgrade");
        migrate(upgradeDatabase, "4");
        UUID legacyIncident = UUID.randomUUID();
        UUID legacyRun = UUID.randomUUID();
        UUID legacyArtifact = UUID.randomUUID();
        try (Connection connection = connection(upgradeDatabase); Statement statement = connection.createStatement()) {
            assertEquals("4", queryString(connection,
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"));
            assertTrue(queryBoolean(connection,
                    "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uq_incident_one_active_run')"));
            statement.executeUpdate("INSERT INTO opspilot.incident VALUES ('" + legacyIncident
                    + "','sample-system','OPEN',now())");
            statement.executeUpdate("INSERT INTO opspilot.incident_run (run_id, incident_id, status) VALUES ('"
                    + legacyRun + "','" + legacyIncident + "','CREATED')");
            statement.executeUpdate("INSERT INTO opspilot.artifact "
                    + "(artifact_id, run_id, uri, sha256, media_type, access_level) VALUES ('"
                    + legacyArtifact + "','" + legacyRun + "','artifact://legacy','" + "c".repeat(64)
                    + "','application/json','INTERNAL')");
        }
        migrate(upgradeDatabase, null);
        try (Connection connection = connection(upgradeDatabase); Statement statement = connection.createStatement()) {
            assertEquals("12", queryString(connection,
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"));
            assertTrue(queryBoolean(connection,
                    "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'uq_incident_one_active_run')"));
            assertEquals(1, queryInt(connection,
                    "SELECT count(*) FROM opspilot.artifact WHERE artifact_id = '" + legacyArtifact
                            + "' AND object_key = '" + legacyArtifact + "' AND size_bytes = 0"));
            assertEquals("legacy", queryString(connection,
                    "SELECT model_configuration_version FROM opspilot.incident_run WHERE run_id = '"
                            + legacyRun + "'"));
            assertTrue(queryBoolean(connection,
                    "SELECT effective_model_configuration_json ->> 'legacy' = 'true' "
                            + "FROM opspilot.incident_run WHERE run_id = '" + legacyRun + "'"));

            statement.execute("SET ROLE evidence_agent_role");
            PSQLException domainDenied = assertThrows(PSQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO opspilot.incident (incident_id,target_system_id,status) VALUES ('"
                            + UUID.randomUUID() + "','forbidden','OPEN')"));
            assertEquals("42501", domainDenied.getSQLState());
            PSQLException groundTruthDenied = assertThrows(PSQLException.class,
                    () -> statement.executeQuery("SELECT * FROM opspilot_eval.ground_truth"));
            assertEquals("42501", groundTruthDenied.getSQLState());
        }

        String emptyDatabase = createDatabase("wp07_t04_empty");
        migrate(emptyDatabase, null);
        try (Connection connection = connection(emptyDatabase);
             Connection upgraded = connection(upgradeDatabase)) {
            assertTrue(queryQualifiedTableSet(connection).containsAll(PHASE0_TABLES));
            assertEquals(schemaDigest(connection), schemaDigest(upgraded));
        }
    }

    @Test
    @Order(5)
    void runCreationFreezesSecretFreeEffectiveConfigurationAcrossHotUpdates() throws Exception {
        String database = createDatabase("wp11_t05");
        migrate(database, null);
        UUID firstIncident = UUID.randomUUID();
        UUID secondIncident = UUID.randomUUID();
        UUID rejectedIncident = UUID.randomUUID();
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        ObjectNode modelV1 = (ObjectNode) JSON.readTree("""
                {"schemaVersion":"1.0.0","roles":{"DIAGNOSIS":{"maxCalls":5,
                "secretRef":"env:OPENAI_API_KEY","fieldSources":{"MAX_CALLS":{
                "layer":"TASK_OVERRIDE","sourceRef":"task-v3"}}}}}
                """);
        ObjectNode knowledgeV1 = (ObjectNode) JSON.readTree("""
                {"schemaVersion":"1.0.0","collectionRef":"operations","activeRevision":"revision-1"}
                """);
        RunConfigurationSnapshot frozenV1 = new RunConfigurationSnapshot(
                "model-v1", "knowledge-v1", modelV1, knowledgeV1);
        ((ObjectNode) modelV1.at("/roles/DIAGNOSIS")).put("maxCalls", 999);

        try (Connection connection = connection(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO opspilot.incident (incident_id,target_system_id,status) VALUES "
                    + "('" + firstIncident + "','sample-system','OPEN'),"
                    + "('" + secondIncident + "','sample-system','OPEN'),"
                    + "('" + rejectedIncident + "','sample-system','OPEN')");
            statement.executeUpdate("INSERT INTO opspilot.model_provider (provider_id,provider_key,display_name) VALUES ('"
                    + providerId + "','openai-primary','OpenAI Primary')");
            statement.executeUpdate("INSERT INTO opspilot.model_profile "
                    + "(profile_id,provider_id,profile_key,purpose,config_json) VALUES ('"
                    + profileId + "','" + providerId
                    + "','diagnosis','CHAT','{\"schemaVersion\":\"1.0.0\",\"model\":\"v1\"}')");

            ActiveRunRepository repository = new ActiveRunRepository();
            repository.createActiveRun(connection, firstIncident, firstRun, frozenV1);
            statement.executeUpdate("UPDATE opspilot.model_profile SET config_json = "
                    + "'{\"schemaVersion\":\"1.0.0\",\"model\":\"v2\"}' WHERE profile_id = '" + profileId + "'");
            ObjectNode modelV2 = (ObjectNode) frozenV1.effectiveModelConfiguration();
            ((ObjectNode) modelV2.at("/roles/DIAGNOSIS")).put("maxCalls", 9);
            repository.createActiveRun(connection, secondIncident, secondRun,
                    new RunConfigurationSnapshot("model-v2", "knowledge-v2", modelV2,
                            (ObjectNode) JSON.readTree("""
                                    {"schemaVersion":"1.0.0","collectionRef":"operations","activeRevision":"revision-2"}
                                    """)));

            RunConfigurationSnapshot storedV1 = repository.findConfigurationSnapshot(connection, firstRun);
            RunConfigurationSnapshot storedV2 = repository.findConfigurationSnapshot(connection, secondRun);
            assertEquals(5, storedV1.effectiveModelConfiguration()
                    .at("/roles/DIAGNOSIS/maxCalls").intValue());
            assertEquals("TASK_OVERRIDE", storedV1.effectiveModelConfiguration()
                    .at("/roles/DIAGNOSIS/fieldSources/MAX_CALLS/layer").textValue());
            assertEquals("revision-1", storedV1.effectiveKnowledgeConfiguration()
                    .at("/activeRevision").textValue());
            assertEquals(9, storedV2.effectiveModelConfiguration()
                    .at("/roles/DIAGNOSIS/maxCalls").intValue());
            assertEquals("model-v1", storedV1.modelConfigurationVersion());
            assertEquals("model-v2", storedV2.modelConfigurationVersion());

            ObjectNode illegal = JSON.createObjectNode()
                    .put("schemaVersion", "1.0.0")
                    .put("apiKey", "plain-text-secret");
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> new RunConfigurationSnapshot("model-v3", "knowledge-v3", illegal, knowledgeV1));
            assertTrue(rejected.getMessage().startsWith("RUN_CONFIGURATION_CONTAINS_SECRET:"));

            PSQLException databaseRejected = assertThrows(PSQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO opspilot.incident_run (run_id,incident_id,status,model_configuration_version,"
                            + "knowledge_configuration_version,effective_model_configuration_json,"
                            + "effective_knowledge_configuration_json) VALUES ('" + UUID.randomUUID() + "','"
                            + rejectedIncident + "','CREATED','model-v3','knowledge-v3',"
                            + "'{\"schemaVersion\":\"1.0.0\",\"password\":\"plain\"}',"
                            + "'{\"schemaVersion\":\"1.0.0\"}')"));
            assertEquals("23514", databaseRejected.getSQLState());
        }
    }

    @Test
    @Order(6)
    void everyVersionMigratesOnTheLockedEmptyDatabaseAndHasStableChecksums() throws Exception {
        for (int version = 1; version <= 8; version++) {
            String database = createDatabase("wp10_empty_v" + version);
            migrate(database, Integer.toString(version));
            try (Connection connection = connection(database)) {
                assertEquals(Integer.toString(version), queryString(connection,
                        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                                + "ORDER BY installed_rank DESC LIMIT 1"));
                assertEquals(version, queryInt(connection,
                        "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
                assertEquals(0, queryInt(connection,
                        "SELECT count(*) FROM flyway_schema_history "
                                + "WHERE success AND version IS NOT NULL AND checksum IS NULL"));
            }
            var validation = Flyway.configure()
                    .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(Integer.toString(version))
                    .load().validateWithResult();
            assertTrue(validation.validationSuccessful, validation.errorDetails == null
                    ? "validation failed" : validation.errorDetails.errorMessage);
        }
    }

    private static Object createActiveRun(String database, UUID incidentId, UUID runId,
                                          CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = connection(database)) {
            connection.setAutoCommit(false);
            ready.countDown();
            start.await();
            try {
                new ActiveRunRepository().createActiveRun(connection, incidentId, runId);
                connection.commit();
                return runId;
            } catch (ActiveRunExistsException conflict) {
                return conflict;
            }
        }
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection connection = connection("postgres"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + name);
        }
        return name;
    }

    private static void migrate(String database, String target) {
        var configuration = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    private static String resource(String path) throws IOException {
        try (var stream = PostgresPhase0MigrationTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> queryQualifiedTableSet(Connection connection) throws SQLException {
        return querySet(connection, """
                SELECT table_schema || '.' || table_name
                FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_schema IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
                """);
    }

    private static Set<String> querySet(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            var values = new java.util.HashSet<String>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return Set.copyOf(values);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String schemaDigest(Connection connection) throws SQLException {
        return queryString(connection, """
                SELECT md5(string_agg(item, chr(10) ORDER BY item))
                FROM (
                    SELECT 'column|' || table_schema || '.' || table_name || '|' || ordinal_position
                           || '|' || column_name || '|' || data_type || '|' || is_nullable AS item
                    FROM information_schema.columns
                    WHERE table_schema IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
                    UNION ALL
                    SELECT 'constraint|' || n.nspname || '.' || c.relname || '|' || con.conname
                           || '|' || pg_get_constraintdef(con.oid)
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
                    UNION ALL
                    SELECT 'index|' || schemaname || '.' || tablename || '|' || indexname || '|' || indexdef
                    FROM pg_indexes
                    WHERE schemaname IN ('opspilot','opspilot_a2a','sample','opspilot_eval')
                ) catalog
                """);
    }
}
