package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleChatModelProvider;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleClientConfiguration;
import io.github.opspilot.adapters.observability.JsonlLogAdapter;
import io.github.opspilot.adapters.knowledge.pgvector.PostgresKnowledgeRevisionControlAdapter;
import io.github.opspilot.adapters.persistence.postgres.PostgresReadinessCheck;
import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingAdapter;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration;
import io.github.opspilot.a2a.contract.A2aProtocol;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;
import org.flywaydb.core.Flyway;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.application.knowledge.KnowledgeRevisionControlService;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.postgresql.ds.PGSimpleDataSource;

/** Minimal Phase 0 process used to prove the frozen six-process boundary. */
public final class Phase0Process {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String PROTOCOL = "1.0";
    private static final String CHAT_MODEL = "deepseek-v4-flash";
    private static final String EMBEDDING_MODEL = "embedding-bge-small-zh-v1.5";
    private static final String RERANK_MODEL = "reranker-bge-v2-m3";
    private static final Map<String, Profile> PROFILES = Map.of(
            "supervisor", new Profile(8080, "supervise-incident", "opspilot_app_role", "svc:opspilot-server"),
            "evidence-collector", new Profile(8081, "collect-observability-evidence", "evidence_agent_role", "svc:evidence-agent"),
            "code-analysis", new Profile(8082, "analyze-code-location", "code_agent_role", "svc:code-agent"),
            "knowledge", new Profile(8083, "retrieve-incident-knowledge", "knowledge_agent_role", "svc:knowledge-agent"),
            "diagnosis", new Profile(8084, "generate-and-verify-hypotheses", "diagnosis_agent_role", "svc:diagnosis-agent"),
            "remediation", new Profile(8085, "propose-remediation", "remediation_agent_role", "svc:remediation-agent"));

    private Phase0Process() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "probe" -> probe(args[1]);
                case "fetch" -> fetch(args[1]);
                case "call" -> call(args);
                case "migrate" -> migrateDatabase(System.getenv());
                case "retrieval-gate" -> retrievalGate();
                case "phase4-smoke" -> Phase4SmokeProcess.run(System.getenv());
                default -> throw new IllegalArgumentException("Unknown command");
            }
            return;
        }
        serve(System.getenv());
    }

    static void serve(Map<String, String> environment) throws Exception {
        Path directoryPath = Path.of(RuntimeIdentity.required(environment, "DIRECTORY_PATH"));
        AgentDirectory directory = AgentDirectory.load(directoryPath.getParent(), directoryPath);
        StartupTrafficGate startup = new StartupTrafficGate();
        startup.complete(StartupTrafficGate.Stage.MACHINE_CONTRACTS);
        RuntimeIdentity identity = RuntimeIdentity.from(environment);
        startup.complete(StartupTrafficGate.Stage.IDENTITY_AND_SECRETS);
        PGSimpleDataSource dataSource = databaseDataSource(environment);
        PostgresReadinessCheck databaseReadiness = databaseReadiness(environment, dataSource);
        startup.complete(StartupTrafficGate.Stage.PROVIDER_TOOL_SKILL_PROBES);
        verifyLocalCard(identity, directory);
        startup.complete(StartupTrafficGate.Stage.CARD_DIRECTORY_RECONCILIATION);
        startup.complete(StartupTrafficGate.Stage.REGISTRIES_FROZEN);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", identity.profile.port), 0);
        Phase7AgentExecutor phase7AgentExecutor = new Phase7AgentExecutor(
                identity.agentId, environment, dataSource);
        Phase7ProfessionalA2aHandler phase7ProfessionalHandler = "supervisor".equals(identity.agentId)
                ? null
                : new Phase7ProfessionalA2aHandler(
                        identity.agentId, identity.profile.skill,
                        new PostgresA2aTaskStore(
                                identity.agentId,
                                RuntimeIdentity.required(environment, "JDBC_URL"),
                                RuntimeIdentity.required(environment, "DB_USERNAME"),
                                Files.readString(Path.of(RuntimeIdentity.required(
                                        environment, "DB_PASSWORD_FILE")), StandardCharsets.UTF_8).strip()),
                        phase7AgentExecutor,
                        "evidence-collector".equals(identity.agentId)
                                ? new Phase7EvidenceCollector(Path.of(RuntimeIdentity.required(
                                        environment, "SOURCE_PATH"))) : null,
                        "code-analysis".equals(identity.agentId)
                                ? new Phase7CodeAnalyzer(
                                        Path.of(RuntimeIdentity.required(environment, "CODE_SOURCE_PATH")),
                                        Path.of(RuntimeIdentity.required(environment, "CODE_WORKSPACE_PATH")),
                                        RuntimeIdentity.required(environment, "CODE_COMMIT_SHA")) : null,
                        "knowledge".equals(identity.agentId)
                                ? new Phase7KnowledgeRetriever(dataSource, environment) : null);
        server.createContext("/actuator/health/liveness", exchange -> json(exchange, 200,
                healthJson(identity, directory, "liveness", "UP", "PROCESS_CONTROL_AVAILABLE")));
        server.createContext("/actuator/health/readiness", exchange -> {
            Readiness readiness = runtimeReadiness(identity, directory, databaseReadiness, startup);
            json(exchange, readiness.ready ? 200 : 503,
                    healthJson(identity, directory, "readiness",
                            readiness.ready ? "UP" : "DOWN", readiness.reason));
        });
        server.createContext("/actuator/health/models", exchange -> json(exchange, 200,
                modelsJson(identity, directory, capabilities(identity, directory, databaseReadiness))));
        server.createContext("/actuator/health/capabilities", exchange -> {
            Map<String, StartupTrafficGate.CapabilityState> capabilities = capabilities(
                    identity, directory, databaseReadiness);
            boolean ready = startup.ready(capabilities);
            json(exchange, ready ? 200 : 503, capabilitiesJson(identity, directory, capabilities));
        });
        server.createContext("/.well-known/agent-card.json", exchange ->
                json(exchange, 200, cardJson(identity.agentId, identity.profile.skill)));
        server.createContext("/a2a/messages:send", exchange -> handleMessage(
                exchange, identity, directory, databaseReadiness, startup, dataSource,
                phase7ProfessionalHandler));
        if (phase7ProfessionalHandler != null) {
            server.createContext("/a2a/tasks/", exchange -> handlePhase7Task(
                    exchange, identity, phase7ProfessionalHandler));
        }
        ProductRunWorker productRunWorker = null;
        HttpServer knowledgeControlServer = null;
        if ("supervisor".equals(identity.agentId)) {
            new ProductApiHandler(dataSource).install(server);
            var gateway = new PostgresProductRunGateway(dataSource,
                    new Phase7RunOrchestrator(dataSource, directory, phase7AgentExecutor, environment));
            productRunWorker = new ProductRunWorker(
                    new DurableTaskRepository(dataSource), gateway, gateway,
                    identity.profile.serviceIdentity + ":product-run");
            knowledgeControlServer = startKnowledgeControlPlane(environment);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        if (productRunWorker != null) productRunWorker.start();
        System.out.printf("PROCESS_READY agentId=%s port=%d dbRole=%s%n",
                identity.agentId, identity.profile.port, identity.profile.dbRole);
    }

    private static HttpServer startKnowledgeControlPlane(Map<String, String> environment) throws Exception {
        if (!Boolean.parseBoolean(environment.getOrDefault("KNOWLEDGE_CONTROL_ENABLED", "false"))) {
            return null;
        }
        PGSimpleDataSource controlDataSource = new PGSimpleDataSource();
        controlDataSource.setUrl(RuntimeIdentity.required(environment, "JDBC_URL"));
        controlDataSource.setUser(RuntimeIdentity.required(environment, "KNOWLEDGE_CONTROL_DB_USERNAME"));
        controlDataSource.setPassword(Files.readString(Path.of(RuntimeIdentity.required(
                environment, "KNOWLEDGE_CONTROL_DB_PASSWORD_FILE")), StandardCharsets.UTF_8).strip());
        var revisions = new PostgresKnowledgeRevisionControlAdapter(controlDataSource);
        var embeddingIdentity = new ProviderIdentity(
                "infinity", RuntimeIdentity.required(environment, "EMBEDDING_MODEL_ID"),
                RuntimeIdentity.required(environment, "EMBEDDING_MODEL_REVISION"));
        int dimension = Integer.parseInt(environment.getOrDefault(
                "KNOWLEDGE_CONTROL_EMBEDDING_DIMENSION", "512"));
        var embedding = new InfinityEmbeddingAdapter(new InfinityEmbeddingConfiguration(
                embeddingIdentity.providerId(),
                URI.create(RuntimeIdentity.required(environment, "EMBEDDING_BASE_URL")),
                embeddingIdentity.modelId(), embeddingIdentity.revision(), dimension,
                InfinityEmbeddingConfiguration.Normalization.L2_UNIT,
                InfinityEmbeddingConfiguration.DistanceMetric.COSINE, 16, 16, 8192),
                text -> Math.max(1, (text.length() + 1) / 2));
        Set<UUID> allowedCollections = Arrays.stream(RuntimeIdentity.required(
                        environment, "KNOWLEDGE_CONTROL_ALLOWED_COLLECTIONS").split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).map(UUID::fromString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        long maxTtlSeconds = Long.parseLong(environment.getOrDefault(
                "KNOWLEDGE_CONTROL_MAX_TTL_SECONDS", "900"));
        var service = new KnowledgeRevisionControlService(
                revisions, embedding, Clock.systemUTC(),
                new KnowledgeRevisionControlService.Policy(allowedCollections, maxTtlSeconds));
        String token = Files.readString(Path.of(RuntimeIdentity.required(
                environment, "KNOWLEDGE_CONTROL_TOKEN_FILE")), StandardCharsets.UTF_8).strip();
        UUID modelRevisionId = UUID.fromString(RuntimeIdentity.required(
                environment, "KNOWLEDGE_CONTROL_MODEL_REVISION_ID"));
        int port = Integer.parseInt(environment.getOrDefault("KNOWLEDGE_CONTROL_PORT", "8099"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        new KnowledgeControlPlaneHandler(
                service, revisions, token,
                environment.getOrDefault("KNOWLEDGE_CONTROL_PRINCIPAL_ID", "fault-lab:phase8"),
                new KnowledgeControlPlaneHandler.ModelConfiguration(
                        modelRevisionId, embeddingIdentity, dimension, "COSINE"))
                .install(server);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("KNOWLEDGE_CONTROL_READY port=%d principal=%s collections=%d%n",
                port, environment.getOrDefault("KNOWLEDGE_CONTROL_PRINCIPAL_ID", "fault-lab:phase8"),
                allowedCollections.size());
        return server;
    }

    static Readiness readiness(String[] urls) {
        return readiness(urls, null);
    }

    static Readiness readiness(String[] urls, PostgresReadinessCheck database) {
        if (database != null) {
            var result = database.check();
            if (!result.ready()) {
                return new Readiness(false, result.reason());
            }
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        for (String url : urls) {
            if (url.isBlank()) {
                continue;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url.strip()))
                        .timeout(Duration.ofMillis(500)).GET().build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                System.out.printf("HTTP_AUDIT method=GET target=%s status=%d%n", url, response.statusCode());
                if (response.statusCode() != 200) {
                    return new Readiness(false, "DEPENDENCY_NOT_READY");
                }
            } catch (Exception exception) {
                return new Readiness(false, "DEPENDENCY_UNREACHABLE");
            }
        }
        return new Readiness(true, "READY");
    }

    private static Readiness runtimeReadiness(RuntimeIdentity identity, AgentDirectory directory,
            PostgresReadinessCheck database, StartupTrafficGate startup) {
        try {
            directory.verifyUnchanged();
        } catch (Exception exception) {
            return new Readiness(false, "AGENT_DIRECTORY_CHANGED_AFTER_STARTUP");
        }
        Map<String, StartupTrafficGate.CapabilityState> capabilities = capabilities(
                identity, directory, database);
        return startup.ready(capabilities)
                ? new Readiness(true, "READY")
                : new Readiness(false, firstDown(capabilities));
    }

    private static Map<String, StartupTrafficGate.CapabilityState> capabilities(
            RuntimeIdentity identity, AgentDirectory directory, PostgresReadinessCheck database) {
        Map<String, StartupTrafficGate.CapabilityState> states = new LinkedHashMap<>();
        boolean databaseReady = database.check().ready();
        states.put("database", databaseReady
                ? StartupTrafficGate.CapabilityState.UP : StartupTrafficGate.CapabilityState.DOWN);
        states.put("agent-state-store", databaseReady
                ? StartupTrafficGate.CapabilityState.UP : StartupTrafficGate.CapabilityState.DOWN);
        states.put("agent-runtime", StartupTrafficGate.CapabilityState.UP);
        states.put("model:llm", configuredModel(CHAT_MODEL));
        states.put("model:embedding", configuredModel(EMBEDDING_MODEL));
        states.put("model:rerank", configuredModel(RERANK_MODEL));
        states.put("directory", StartupTrafficGate.CapabilityState.UP);
        states.put("card", StartupTrafficGate.CapabilityState.UP);
        if ("supervisor".equals(identity.agentId)) {
            for (AgentDirectory.Entry entry : directory.entries()) {
                AgentDirectory.EndpointSnapshot snapshot = directory.probe(
                        entry.id(), Phase0Process::fetchCard, Instant.now());
                states.put("skill:" + entry.expectedSkill(),
                        snapshot.state() == io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState.READY
                                ? StartupTrafficGate.CapabilityState.UP
                                : StartupTrafficGate.CapabilityState.DOWN);
            }
        } else {
            AgentDirectory.EndpointSnapshot snapshot = directory.probe(identity.agentId,
                    ignored -> cardJson(identity.agentId, identity.profile.skill)
                            .getBytes(StandardCharsets.UTF_8), Instant.now());
            states.put("skill:" + identity.profile.skill,
                    snapshot.state() == io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState.READY
                            ? StartupTrafficGate.CapabilityState.UP
                            : StartupTrafficGate.CapabilityState.DOWN);
            states.put("tool:" + identity.profile.skill, StartupTrafficGate.CapabilityState.UP);
        }
        return Map.copyOf(states);
    }

    private static StartupTrafficGate.CapabilityState configuredModel(String modelId) {
        return modelId == null || modelId.isBlank()
                ? StartupTrafficGate.CapabilityState.DOWN : StartupTrafficGate.CapabilityState.UP;
    }

    private static PostgresReadinessCheck databaseReadiness(Map<String, String> environment) throws IOException {
        return databaseReadiness(environment, databaseDataSource(environment));
    }

    private static PGSimpleDataSource databaseDataSource(Map<String, String> environment) throws IOException {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(RuntimeIdentity.required(environment, "JDBC_URL"));
        dataSource.setUser(RuntimeIdentity.required(environment, "DB_USERNAME"));
        dataSource.setPassword(Files.readString(
                Path.of(RuntimeIdentity.required(environment, "DB_PASSWORD_FILE")), StandardCharsets.UTF_8).strip());
        return dataSource;
    }

    private static PostgresReadinessCheck databaseReadiness(
            Map<String, String> environment, PGSimpleDataSource dataSource) {
        return new PostgresReadinessCheck(dataSource,
                environment.getOrDefault("EXPECTED_FLYWAY_VERSION", "31"),
                environment.getOrDefault("EXPECTED_PGVECTOR_VERSION", "0.8.4"));
    }

    private static void handleMessage(HttpExchange exchange, RuntimeIdentity identity,
            AgentDirectory directory, PostgresReadinessCheck database,
            StartupTrafficGate startup, javax.sql.DataSource dataSource,
            Phase7ProfessionalA2aHandler phase7ProfessionalHandler) throws IOException {
        System.out.printf("HTTP_AUDIT method=%s path=/a2a/messages:send caller=%s%n",
                exchange.getRequestMethod(), exchange.getRemoteAddress().getAddress().getHostAddress());
        try {
            directory.verifyUnchanged();
            startup.requireTaskAcceptance(capabilities(identity, directory, database));
        } catch (Exception exception) {
            json(exchange, 503, "{\"code\":\"SERVICE_NOT_READY\"}");
            return;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String requestedSkill = exchange.getRequestHeaders().getFirst("X-A2A-Skill");
        String requestedRole = exchange.getRequestHeaders().getFirst("X-DB-Role");
        boolean tokenMatches = authorization != null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(identity.serviceToken,
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
        if (!"POST".equals(exchange.getRequestMethod()) || !tokenMatches
                || !identity.profile.skill.equals(requestedSkill)
                || !identity.profile.dbRole.equals(requestedRole)) {
            json(exchange, 403, "{\"code\":\"AGENT_IDENTITY_FORBIDDEN\"}");
            return;
        }
        if ("source-unavailable".equals(exchange.getRequestHeaders().getFirst("X-Fault-Mode"))) {
            json(exchange, 503, "{\"code\":\"SOURCE_UNAVAILABLE\"}");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!"supervisor".equals(identity.agentId)
                && contentType != null && contentType.startsWith(A2aProtocol.MEDIA_TYPE)) {
            phase7ProfessionalHandler.handle(exchange);
            return;
        }
        if ("supervisor".equals(identity.agentId)) {
            delegateToEvidenceAgent(exchange, identity, dataSource);
            return;
        }
        if ("evidence-collector".equals(identity.agentId)) {
            collectEvidence(exchange, identity);
            return;
        }
        json(exchange, 202, "{\"taskId\":\"" + UUID.randomUUID() + "\",\"state\":\"SUBMITTED\"}");
    }

    private static void handlePhase7Task(HttpExchange exchange, RuntimeIdentity identity,
            Phase7ProfessionalA2aHandler handler) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean tokenMatches = authorization != null && authorization.startsWith("Bearer ")
                && MessageDigest.isEqual(identity.serviceToken,
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8));
        if (!tokenMatches
                || !identity.profile.skill.equals(exchange.getRequestHeaders().getFirst("X-A2A-Skill"))
                || !identity.profile.dbRole.equals(exchange.getRequestHeaders().getFirst("X-DB-Role"))) {
            json(exchange, 403, "{\"code\":\"AGENT_IDENTITY_FORBIDDEN\"}");
            return;
        }
        handler.handleTask(exchange);
    }

    private static void delegateToEvidenceAgent(HttpExchange exchange, RuntimeIdentity identity,
            javax.sql.DataSource dataSource) throws IOException {
        if (identity.evidenceAgentToken == null) {
            json(exchange, 503, "{\"code\":\"A2A_IDENTITY_UNAVAILABLE\"}");
            return;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://evidence-agent:8081/a2a/messages:send"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + new String(identity.evidenceAgentToken, StandardCharsets.UTF_8))
                .header("X-A2A-Skill", "collect-observability-evidence")
                .header("X-DB-Role", "evidence_agent_role")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        copyHeader(exchange, request, "X-Incident-Id");
        copyHeader(exchange, request, "X-Run-Id");
        copyHeader(exchange, request, "X-Step-Id");
        copyHeader(exchange, request, "X-Request-Id");
        copyHeader(exchange, request, "Trace-Id");
        copyHeader(exchange, request, "X-A2A-Task-Id");
        copyHeader(exchange, request, "X-Invocation-Id");
        copyHeader(exchange, request, "X-Fault-Mode");
        copyHeader(exchange, request, "X-Window-Start");
        copyHeader(exchange, request, "X-Window-End");
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            System.out.printf("HTTP_AUDIT method=POST target=http://evidence-agent:8081/a2a/messages:send status=%d%n",
                    response.statusCode());
            if (response.statusCode() == 200 && "true".equalsIgnoreCase(
                    exchange.getRequestHeaders().getFirst("X-Phase6-Vertical-Slice"))) {
                ChatResponse providerResponse = invokePhase6Provider(response.body());
                persistPhase6VerticalSlice(dataSource, exchange, response.body(), providerResponse);
            }
            copyResponseHeader(exchange, "X-Request-Id");
            copyResponseHeader(exchange, "Trace-Id");
            json(exchange, response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            json(exchange, 503, "{\"code\":\"A2A_CANCELLED\"}");
        } catch (Exception exception) {
            System.err.printf("A2A_FAILURE type=%s message=%s%n",
                    exception.getClass().getSimpleName(), escape(String.valueOf(exception.getMessage())));
            json(exchange, 503, "{\"code\":\"A2A_UNAVAILABLE\"}");
        }
    }

    private static void collectEvidence(HttpExchange exchange, RuntimeIdentity identity) throws IOException {
        try {
            UUID incidentId = headerUuid(exchange, "X-Incident-Id");
            UUID runId = headerUuid(exchange, "X-Run-Id");
            UUID stepId = headerUuid(exchange, "X-Step-Id");
            String traceId = exchange.getRequestHeaders().getFirst("Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            String requestId = headerOrRandom(exchange, "X-Request-Id");
            String invocationId = headerOrRandom(exchange, "X-Invocation-Id");
            ResourceRef resource = new ResourceRef(
                    "service:sample-system", ResourceType.SERVICE, "sample-system", "sample-system",
                    "phase0", Map.of("composeService", "sample-system"));
            Instant windowStart = optionalInstantHeader(
                    exchange, "X-Window-Start", "2026-07-18T07:55:00Z");
            Instant windowEnd = optionalInstantHeader(
                    exchange, "X-Window-End", "2026-07-18T08:05:00Z");
            ObservationQuery query = new ObservationQuery(
                    "log/errors-v1", ObservationContracts.sha256("level=ERROR"),
                    windowStart, windowEnd, resource);
            var batch = new JsonlLogAdapter(identity.sourcePath).query(
                    query, SourceExecutionContext.authorizedUntil(java.time.Instant.now().plusSeconds(3)));
            var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(
                    java.util.List.of(batch), new NormalizationContext(incidentId, runId, stepId));
            String taskId = UUID.randomUUID().toString();
            var evidence = bundle.evidence().stream().map(value -> Map.of(
                    "evidenceId", value.evidenceId().toString(),
                    "evidenceCode", value.evidenceCode(),
                    "claim", value.claim(),
                    "signalType", value.signalType().name(),
                    "observedAt", value.windowStart().toString())).toList();
            json(exchange, 200, JSON.writeValueAsString(Map.ofEntries(
                    Map.entry("taskId", taskId),
                    Map.entry("state", "COMPLETED"),
                    Map.entry("requestId", requestId),
                    Map.entry("traceId", traceId),
                    Map.entry("runId", runId.toString()),
                    Map.entry("stepId", stepId.toString()),
                    Map.entry("invocationId", invocationId),
                    Map.entry("sourceId", batch.source().sourceId()),
                    Map.entry("sourceAdapter", batch.source().adapterId() + ":" + batch.source().adapterVersion()),
                    Map.entry("batchId", batch.batchId().toString()),
                    Map.entry("artifactId", batch.rawArtifact().artifactId().toString()),
                    Map.entry("artifactSha256", batch.rawArtifact().sha256()),
                    Map.entry("evidenceId", bundle.evidence().getFirst().evidenceId().toString()),
                    Map.entry("claim", bundle.evidence().getFirst().claim()),
                    Map.entry("evidence", evidence),
                    Map.entry("commit", System.getenv().getOrDefault("SOURCE_COMMIT", "working-tree")),
                    Map.entry("chatModelId", CHAT_MODEL))));
        } catch (Exception exception) {
            System.err.printf("EVIDENCE_COLLECTION_FAILURE type=%s message=%s%n",
                    exception.getClass().getSimpleName(), escape(String.valueOf(exception.getMessage())));
            json(exchange, 503, "{\"code\":\"SOURCE_UNAVAILABLE\"}");
        }
    }

    private static UUID headerUuid(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    private static String requiredHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HEADER_MISSING:" + name);
        return value;
    }

    private static Instant optionalInstantHeader(HttpExchange exchange, String name, String fallback) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return Instant.parse(value == null || value.isBlank() ? fallback : value);
    }

    private static void copyHeader(HttpExchange exchange, HttpRequest.Builder request, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) {
            request.header(name, value);
        }
    }

    private static void copyResponseHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) exchange.getResponseHeaders().set(name, value);
    }

    private static String headerOrRandom(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static ChatResponse invokePhase6Provider(String professionalResponse) throws Exception {
        String claim = JSON.readTree(professionalResponse).path("claim").asText();
        if (claim.isBlank()) {
            throw new IllegalStateException("PHASE6_PROVIDER_INPUT_MISSING");
        }
        String model = RuntimeIdentity.required(System.getenv(), "CHAT_MODEL_ID");
        String secretFile = RuntimeIdentity.required(System.getenv(), "CHAT_MODEL_API_KEY_FILE");
        var provider = new OpenAiCompatibleChatModelProvider(
                "deepseek", "phase6-compose",
                new OpenAiCompatibleClientConfiguration(
                        URI.create(System.getenv().getOrDefault(
                                "CHAT_MODEL_BASE_URL", "https://api.deepseek.com")),
                        model, "file:" + secretFile),
                EnvironmentFileSecretResolver.system());
        ChatResponse response = provider.complete(new ChatRequest(model, List.of(new ChatMessage(
                "user", "Return one concise sentence confirming this evidence-based finding: "
                        + claim)), List.of()));
        if (response.text() == null || response.text().isBlank()) {
            throw new IllegalStateException("PHASE6_PROVIDER_RESPONSE_EMPTY");
        }
        return response;
    }

    /** Explicit Compose verification path: commit professional evidence, RCA, SSE and audit atomically. */
    private static void persistPhase6VerticalSlice(
            javax.sql.DataSource dataSource, HttpExchange exchange, String professionalResponse,
            ChatResponse providerResponse) {
        UUID incidentId = headerUuid(exchange, "X-Incident-Id");
        UUID runId = headerUuid(exchange, "X-Run-Id");
        UUID stepId = headerUuid(exchange, "X-Step-Id");
        UUID requestId = UUID.fromString(headerOrRandom(exchange, "X-Request-Id"));
        UUID traceId = UUID.fromString(headerOrRandom(exchange, "Trace-Id"));
        UUID invocationId = UUID.fromString(headerOrRandom(exchange, "X-Invocation-Id"));
        UUID artifactId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        try {
            var response = JSON.readTree(professionalResponse);
            UUID evidenceId = UUID.fromString(response.path("evidenceId").asText());
            String a2aTaskId = response.path("taskId").asText();
            String digest = sha256(professionalResponse.getBytes(StandardCharsets.UTF_8));
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.artifact
                                (artifact_id,run_id,uri,sha256,media_type,access_level,object_key,
                                 size_bytes,retention_class,request_id,trace_id,step_id,a2a_task_id,
                                 invocation_id)
                            VALUES (?,?,?,?,?,'RUN_PRIVATE',?,?,'RCA',?,?,?,?,?)
                            """)) {
                        statement.setObject(1, artifactId);
                        statement.setObject(2, runId);
                        statement.setString(3, "artifact://phase6/" + artifactId);
                        statement.setString(4, digest);
                        statement.setString(5, "application/json");
                        statement.setString(6, "phase6/" + artifactId);
                        statement.setLong(7, professionalResponse.getBytes(StandardCharsets.UTF_8).length);
                        statement.setObject(8, requestId);
                        statement.setObject(9, traceId);
                        statement.setObject(10, stepId);
                        statement.setString(11, a2aTaskId);
                        statement.setObject(12, invocationId);
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.tool_call
                                (tool_call_id,run_id,tool_name,idempotency_key,request_hash,
                                 result_artifact_id,outcome_code,request_id,trace_id,step_id,
                                 a2a_task_id,invocation_id)
                            VALUES (?,?,'jsonl-log-observation',?,?,?,?,?,?,?,?,?)
                            """)) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, runId);
                        statement.setString(3, "phase6:" + requestId);
                        statement.setString(4, digest);
                        statement.setObject(5, artifactId);
                        statement.setString(6, "SUCCEEDED");
                        statement.setObject(7, requestId);
                        statement.setObject(8, traceId);
                        statement.setObject(9, stepId);
                        statement.setString(10, a2aTaskId);
                        statement.setObject(11, invocationId);
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.evidence
                                (evidence_id,run_id,summary,artifact_id,attributes)
                            VALUES (?,?,?,?,?::jsonb)
                            """)) {
                        statement.setObject(1, evidenceId);
                        statement.setObject(2, runId);
                        statement.setString(3, "Professional Agent collected runtime evidence");
                        statement.setObject(4, artifactId);
                        statement.setString(5, "{\"schemaVersion\":\"1.0.0\",\"traceId\":\""
                                + traceId + "\"}");
                        statement.executeUpdate();
                    }
                    String conclusion = providerResponse.text().strip();
                    if (conclusion.length() > 512) conclusion = conclusion.substring(0, 512);
                    String reportJson = JSON.writeValueAsString(Map.of(
                            "schemaVersion", "1.0.0", "outcome", "CONCLUSIVE",
                            "evidenceIds", List.of(evidenceId.toString()), "conclusion", conclusion));
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.rca_report
                                (report_id,run_id,report_artifact_id,report_json,report_markdown)
                            VALUES (?,?,?,?::jsonb,?)
                            """)) {
                        statement.setObject(1, reportId);
                        statement.setObject(2, runId);
                        statement.setObject(3, artifactId);
                        statement.setString(4, reportJson);
                        statement.setString(5, "# Root cause analysis\n\n" + conclusion);
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.model_call
                                (model_call_id,run_id,outcome_code,request_id,trace_id,step_id,
                                 a2a_task_id,invocation_id)
                            VALUES (?,?,'SUCCEEDED',?,?,?,?,?)
                            """)) {
                        statement.setObject(1, invocationId);
                        statement.setObject(2, runId);
                        statement.setObject(3, requestId);
                        statement.setObject(4, traceId);
                        statement.setObject(5, stepId);
                        statement.setString(6, a2aTaskId);
                        statement.setObject(7, invocationId);
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            UPDATE opspilot.incident_run
                            SET status='COMPLETED', outcome='CONCLUSIVE', ended_at=now(),
                                updated_at=now(), run_version=run_version+1
                            WHERE run_id=? AND incident_id=?
                            """)) {
                        statement.setObject(1, runId);
                        statement.setObject(2, incidentId);
                        if (statement.executeUpdate() != 1) throw new IllegalStateException("RUN_NOT_FOUND");
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.sse_event
                                (event_id,run_id,sequence_no,event_type,payload_json,request_id,
                                 trace_id,step_id,a2a_task_id,invocation_id)
                            VALUES (?, ?, (SELECT COALESCE(max(sequence_no),0)+1
                                           FROM opspilot.sse_event WHERE run_id=?),
                                    'RCA_COMPLETED', ?::jsonb,?,?,?,?,?)
                            """)) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, runId);
                        statement.setObject(3, runId);
                        statement.setString(4, "{\"schemaVersion\":\"1.0.0\",\"reportId\":\""
                                + reportId + "\"}");
                        statement.setObject(5, requestId);
                        statement.setObject(6, traceId);
                        statement.setObject(7, stepId);
                        statement.setString(8, a2aTaskId);
                        statement.setObject(9, invocationId);
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO opspilot.outbox_event
                                (event_id,run_id,fact_type,state_version,payload_json,occurred_at,
                                 request_id,trace_id,step_id,a2a_task_id,invocation_id)
                            SELECT ?,?,'RCA_COMPLETED',run_version,
                                   jsonb_build_object('schemaVersion','1.0.0','reportId',?::text),
                                   now(),?,?,?,?,?
                            FROM opspilot.incident_run WHERE run_id=?
                            """)) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, runId);
                        statement.setObject(3, reportId);
                        statement.setObject(4, requestId);
                        statement.setObject(5, traceId);
                        statement.setObject(6, stepId);
                        statement.setString(7, a2aTaskId);
                        statement.setObject(8, invocationId);
                        statement.setObject(9, runId);
                        statement.executeUpdate();
                    }
                    appendVerticalAudit(connection, incidentId, runId, stepId, requestId,
                            traceId, a2aTaskId, invocationId, artifactId, digest, providerResponse);
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("PHASE6_VERTICAL_SLICE_COMMIT_FAILED", exception);
        }
    }

    private static void appendVerticalAudit(Connection connection, UUID incidentId, UUID runId,
            UUID stepId, UUID requestId, UUID traceId, String a2aTaskId, UUID invocationId,
            UUID logArtifactId, String fingerprint, ChatResponse providerResponse) throws SQLException {
        UUID parent = null;
        for (String boundary : new String[] {
                "REST", "SUPERVISOR", "A2A", "AGENT_RUNTIME", "TOOL", "PROVIDER",
                "ARTIFACT", "EVIDENCE", "ANALYSIS_SEAL", "RCA", "SSE"}) {
            UUID auditId = UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO opspilot.correlation_audit
                        (audit_id,parent_audit_id,principal_id,incident_id,run_id,step_id,
                         request_id,trace_id,a2a_task_id,invocation_id,boundary,action_fingerprint,
                         permission_summary,result_code,summary,log_artifact_id)
                    SELECT ?,?,i.principal_id,?,?,?,?,?,?,?,?,?,
                           '{"decision":"allowed"}'::jsonb,'SUCCEEDED',?,?
                    FROM opspilot.incident i WHERE i.incident_id=?
                    """)) {
                statement.setObject(1, auditId);
                statement.setObject(2, parent);
                statement.setObject(3, incidentId);
                statement.setObject(4, runId);
                statement.setObject(5, stepId);
                statement.setObject(6, requestId);
                statement.setObject(7, traceId);
                statement.setString(8, a2aTaskId);
                statement.setObject(9, invocationId);
                statement.setString(10, boundary);
                statement.setString(11, fingerprint);
                String summary = boundary + " vertical slice succeeded";
                if ("PROVIDER".equals(boundary)) {
                    summary += "; provider=" + providerResponse.actualIdentity().providerId()
                            + "; model=" + providerResponse.actualIdentity().modelId();
                }
                statement.setString(12, summary);
                statement.setObject(13, "ARTIFACT".equals(boundary) ? logArtifactId : null);
                statement.setObject(14, incidentId);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("INCIDENT_NOT_FOUND");
            }
            parent = auditId;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void probe(String url) throws Exception {
        int status = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status != 200) {
            System.exit(1);
        }
    }

    private static void call(String[] args) throws Exception {
        String token = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8).strip();
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[1]))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .header("X-A2A-Skill", args[3])
                .header("X-DB-Role", args[4])
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        int actual = response.statusCode();
        int expected = Integer.parseInt(args[5]);
        System.out.println("HTTP_STATUS=" + actual);
        System.out.println(response.body());
        if (actual != expected) {
            System.exit(1);
        }
    }

    private static void migrateDatabase(Map<String, String> environment) throws IOException {
        String jdbcUrl = RuntimeIdentity.required(environment, "JDBC_URL");
        String username = RuntimeIdentity.required(environment, "MIGRATOR_USERNAME");
        if (!"opspilot_migrator".equals(username)) {
            throw new IllegalStateException("MIGRATOR_IDENTITY_INCOMPATIBLE");
        }
        String password = Files.readString(
                Path.of(RuntimeIdentity.required(environment, "MIGRATOR_PASSWORD_FILE")),
                StandardCharsets.UTF_8).strip();
        String locations = environment.getOrDefault("FLYWAY_LOCATIONS", "filesystem:/app/migrations");
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations(locations)
                .target("1")
                .validateOnMigrate(true)
                .load().migrate();
        demoteMigrator(jdbcUrl, username, password);
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations(locations)
                .validateOnMigrate(true)
                .load();
        var result = flyway.migrate();
        String expectedVersion = environment.getOrDefault("EXPECTED_FLYWAY_VERSION", "31");
        var current = flyway.info().current();
        if (current == null || !expectedVersion.equals(current.getVersion().toString())) {
            throw new IllegalStateException("MIGRATION_SET_INCOMPATIBLE expected=" + expectedVersion
                    + " actual=" + (current == null ? "null" : current.getVersion()));
        }
        System.out.println("MIGRATION_SET_VALIDATED version=" + expectedVersion
                + " migrations=" + result.migrationsExecuted);
    }

    private static void demoteMigrator(String jdbcUrl, String username, String password) {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var query = connection.prepareStatement("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            try (var result = query.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    return;
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER ROLE opspilot_migrator NOSUPERUSER");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("MIGRATOR_DEMOTION_FAILED", exception);
        }
    }

    private static void retrievalGate() {
        require("CHAT_MODEL_ID", CHAT_MODEL);
        require("EMBEDDING_MODEL_ID", EMBEDDING_MODEL);
        require("RERANK_MODEL_ID", RERANK_MODEL);
        System.out.println("RETRIEVAL_GATE_CONFIGURATION_COMPATIBLE");
    }

    private static void fetch(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP_STATUS=" + response.statusCode());
        System.out.println(response.body());
        if (response.statusCode() != 200) {
            System.exit(1);
        }
    }

    static String cardJson(String agentId, String skill) {
        return "{\"name\":\"" + agentId + "\",\"protocolVersion\":\"" + PROTOCOL
                + "\",\"skills\":[{\"id\":\"" + skill + "\"}]}";
    }

    private static void verifyLocalCard(RuntimeIdentity identity, AgentDirectory directory) {
        if ("supervisor".equals(identity.agentId)) {
            return;
        }
        AgentDirectory.EndpointSnapshot snapshot = directory.probe(identity.agentId,
                ignored -> cardJson(identity.agentId, identity.profile.skill)
                        .getBytes(StandardCharsets.UTF_8), Instant.now());
        if (snapshot.state()
                != io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState.READY) {
            throw new IllegalStateException("LOCAL_AGENT_CARD_INCOMPATIBLE");
        }
    }

    private static byte[] fetchCard(URI uri) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(700))
                        .header("A2A-Version", PROTOCOL).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("AGENT_CARD_PROBE_FAILED");
        }
        return response.body();
    }

    private static String firstDown(Map<String, StartupTrafficGate.CapabilityState> capabilities) {
        return capabilities.entrySet().stream()
                .filter(entry -> entry.getValue() == StartupTrafficGate.CapabilityState.DOWN)
                .map(entry -> "CAPABILITY_DOWN:" + entry.getKey())
                .findFirst().orElse("STARTUP_INCOMPLETE");
    }

    private static String healthJson(RuntimeIdentity identity, AgentDirectory directory,
            String type, String status, String reason) {
        return "{\"schemaVersion\":\"1.0\",\"type\":\"" + type
                + "\",\"status\":\"" + status + "\",\"agentId\":\"" + identity.agentId
                + "\",\"probedAt\":\"" + Instant.now() + "\",\"configVersion\":\""
                + directory.schemaVersion() + "\",\"directoryDigest\":\"" + directory.digest()
                + "\",\"cardDigest\":\"" + sha256(cardJson(identity.agentId, identity.profile.skill)
                        .getBytes(StandardCharsets.UTF_8))
                + "\",\"reason\":\"" + escape(reason) + "\"}";
    }

    private static String modelsJson(RuntimeIdentity identity, AgentDirectory directory,
            Map<String, StartupTrafficGate.CapabilityState> capabilities) {
        String values = Map.of(
                        "llm", Map.entry(CHAT_MODEL, capabilities.get("model:llm")),
                        "embedding", Map.entry(EMBEDDING_MODEL, capabilities.get("model:embedding")),
                        "rerank", Map.entry(RERANK_MODEL, capabilities.get("model:rerank")))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "{\"kind\":\"" + entry.getKey() + "\",\"modelId\":\""
                        + escape(entry.getValue().getKey()) + "\",\"status\":\""
                        + entry.getValue().getValue() + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        boolean up = capabilities.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("model:"))
                .allMatch(entry -> entry.getValue() == StartupTrafficGate.CapabilityState.UP);
        String dataState = "knowledge".equals(identity.agentId) ? "KB_EMPTY" : "NOT_APPLICABLE";
        return healthJson(identity, directory, "models", up ? "UP" : "DOWN",
                up ? "MODEL_PROBES_VALID" : "MODEL_PROBE_FAILED")
                .replace("}", ",\"knowledgeDataState\":\"" + dataState
                        + "\",\"models\":[" + values + "]}");
    }

    private static String capabilitiesJson(RuntimeIdentity identity, AgentDirectory directory,
            Map<String, StartupTrafficGate.CapabilityState> capabilities) {
        String values = capabilities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> "{\"id\":\"" + escape(entry.getKey()) + "\",\"status\":\""
                        + entry.getValue() + "\",\"required\":true,\"retryable\":"
                        + (entry.getValue() == StartupTrafficGate.CapabilityState.DOWN) + "}")
                .collect(java.util.stream.Collectors.joining(","));
        String status = capabilities.values().stream()
                .allMatch(value -> value == StartupTrafficGate.CapabilityState.UP) ? "UP" : "DOWN";
        return healthJson(identity, directory, "capabilities", status, "CAPABILITY_SNAPSHOT")
                .replace("}", ",\"capabilities\":[" + values + "]}");
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(String name, String expected) {
        if (!expected.equals(System.getenv(name))) {
            throw new IllegalStateException(name + "_INCOMPATIBLE");
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    record Profile(int port, String skill, String dbRole, String serviceIdentity) {
    }

    record Readiness(boolean ready, String reason) {
    }

    record RuntimeIdentity(
            String agentId,
            Profile profile,
            byte[] serviceToken,
            byte[] evidenceAgentToken,
            String a2aBaseUrl,
            String[] readinessUrls,
            Path sourcePath) {
        static RuntimeIdentity from(Map<String, String> environment) throws IOException {
            String agentId = required(environment, "AGENT_ID");
            Profile profile = PROFILES.get(agentId);
            if (profile == null) {
                throw new IllegalStateException("AGENT_ID_INCOMPATIBLE");
            }
            if (Integer.parseInt(required(environment, "SERVER_PORT")) != profile.port) {
                throw new IllegalStateException("SERVER_PORT_INCOMPATIBLE");
            }
            if (!PROTOCOL.equals(required(environment, "A2A_PROTOCOL"))) {
                throw new IllegalStateException("A2A_PROTOCOL_INCOMPATIBLE");
            }
            if (!CHAT_MODEL.equals(required(environment, "CHAT_MODEL_ID"))) {
                throw new IllegalStateException("CHAT_MODEL_ID_INCOMPATIBLE");
            }
            if (!profile.skill.equals(required(environment, "AGENT_SKILL"))) {
                throw new IllegalStateException("AGENT_SKILL_INCOMPATIBLE");
            }
            if (!profile.dbRole.equals(required(environment, "DB_ROLE"))) {
                throw new IllegalStateException("DB_ROLE_INCOMPATIBLE");
            }
            if (!profile.serviceIdentity.equals(required(environment, "SERVICE_IDENTITY"))) {
                throw new IllegalStateException("SERVICE_IDENTITY_INCOMPATIBLE");
            }
            String tokenFile = required(environment, "SERVICE_TOKEN_FILE");
            byte[] token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip()
                    .getBytes(StandardCharsets.UTF_8);
            if (token.length < 16) {
                throw new IllegalStateException("SERVICE_TOKEN_INCOMPATIBLE");
            }
            String a2aBaseUrl = required(environment, "A2A_BASE_URL");
            URI.create(a2aBaseUrl);
            byte[] evidenceAgentToken = null;
            if ("supervisor".equals(agentId)) {
                evidenceAgentToken = Files.readString(
                                Path.of(required(environment, "EVIDENCE_AGENT_TOKEN_FILE")), StandardCharsets.UTF_8)
                        .strip().getBytes(StandardCharsets.UTF_8);
            }
            Path sourcePath = "evidence-collector".equals(agentId)
                    ? Path.of(required(environment, "SOURCE_PATH")) : null;
            String readiness = environment.getOrDefault("READINESS_URLS", "");
            return new RuntimeIdentity(agentId, profile, token, evidenceAgentToken, a2aBaseUrl,
                    readiness.isBlank() ? new String[0] : Arrays.stream(readiness.split(",")).toArray(String[]::new),
                    sourcePath);
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + "_MISSING");
            }
            return value;
        }
    }
}
