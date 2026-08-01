package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleChatModelProvider;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleClientConfiguration;
import io.github.opspilot.adapters.persistence.postgres.PostgresUsageLedgerRepository;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.application.profile.AgentProfile;
import io.github.opspilot.core.port.agent.AgentExecutionService.CancellationToken;
import io.github.opspilot.core.port.agent.AgentExecutionService.ExecutionLimits;
import io.github.opspilot.core.port.agent.AgentExecutionService.ExecutionRequest;
import io.github.opspilot.core.port.agent.AgentExecutionService.ExecutionResult;
import io.github.opspilot.core.port.agent.AgentExecutionService.ExecutionSession;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.ChatPort.ChatInvocation;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import io.github.opspilot.core.port.agent.ToolPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.repository.UsageLedgerRepository.UsageRecord;
import io.github.opspilot.runtime.agentscope.AgentScopeExecutionService;
import io.github.opspilot.runtime.agentscope.BundledAgentProfiles;
import io.github.opspilot.runtime.agentscope.PostgresAgentStateStore;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Runs one production AgentScope loop for the process-owned role. */
final class Phase7AgentExecutor implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final String agentId;
    private final String chatModel;
    private final AgentProfile profile;
    private final ChatPort configuredModel;
    private final PostgresAgentStateStore stateStore;
    private final RuntimeAuditSink audit;
    private final PostgresUsageLedgerRepository usage;

    Phase7AgentExecutor(
            String agentId, Map<String, String> environment, DataSource dataSource) throws IOException {
        this.agentId = required(agentId, "agentId");
        this.chatModel = required(environment.get("CHAT_MODEL_ID"), "CHAT_MODEL_ID");
        this.profile = new BundledAgentProfiles().loadAll().stream()
                .filter(candidate -> processAgentId(candidate.role()).equals(agentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AGENT_PROFILE_NOT_FOUND:" + agentId));
        String jdbcUrl = required(environment.get("JDBC_URL"), "JDBC_URL");
        String username = required(environment.get("DB_USERNAME"), "DB_USERNAME");
        String password = java.nio.file.Files.readString(java.nio.file.Path.of(
                required(environment.get("DB_PASSWORD_FILE"), "DB_PASSWORD_FILE")),
                StandardCharsets.UTF_8).strip();
        var provider = new OpenAiCompatibleChatModelProvider(
                required(environment.getOrDefault("CHAT_PROVIDER_ID", "deepseek"), "CHAT_PROVIDER_ID"),
                required(environment.getOrDefault("CHAT_MODEL_REVISION", chatModel), "CHAT_MODEL_REVISION"),
                new OpenAiCompatibleClientConfiguration(
                        URI.create(required(environment.get("CHAT_MODEL_BASE_URL"), "CHAT_MODEL_BASE_URL")),
                        chatModel,
                        "file:" + required(environment.get("CHAT_MODEL_API_KEY_FILE"),
                                "CHAT_MODEL_API_KEY_FILE")),
                EnvironmentFileSecretResolver.system(),
                providerCapabilities(profile));
        this.configuredModel = new ConfiguredModelChatPort(chatModel, provider);
        this.audit = event -> System.out.printf(
                "AGENTSCOPE_AUDIT agentId=%s type=%s round=%d inputTokens=%s outputTokens=%s errorCode=%s%n",
                agentId, event.type(), event.round(), event.inputTokens(), event.outputTokens(),
                event.attributes().get("errorCode"));
        this.stateStore = new PostgresAgentStateStore(agentId, jdbcUrl, username, password);
        this.usage = new PostgresUsageLedgerRepository(dataSource);
    }

    String execute(String a2aTaskId, String runId, String incidentId, String input) throws Exception {
        return execute(a2aTaskId, runId, incidentId, input, List.of());
    }

    String execute(
            String a2aTaskId, String runId, String incidentId, String input,
            List<ToolPort> tools) throws Exception {
        UUID run = UUID.fromString(required(runId, "runId"));
        String task = required(a2aTaskId, "a2aTaskId");
        Set<String> allowedTools = profile.toolPolicy().allowedTools().stream()
                .map(AgentProfile.ToolRef::toolId).collect(java.util.stream.Collectors.toSet());
        List<String> toolIds = tools.stream().map(ToolPort::name).toList();
        if (!allowedTools.containsAll(toolIds)) {
            throw new IllegalArgumentException("AGENT_TOOL_NOT_ALLOWED:" + agentId);
        }
        Instant deadline = Instant.now().plusSeconds(profile.budget().deadlineSeconds());
        String prompt = loadPrompt(profile.prompt().systemPolicyTemplateId()) + "\n\n"
                + loadPrompt(profile.prompt().templateId())
                + (toolIds.isEmpty() ? "" : "\n\nYou MUST invoke every provided read-only tool exactly once "
                        + "before returning the final JSON. Do not invent tool results.") + "\n\n"
                + outputContract();
        UUID invocationId = UUID.randomUUID();
        ExecutionResult result;
        try (var runtime = new AgentScopeExecutionService(configuredModel, tools, stateStore, audit)) {
            result = runtime.execute(new ExecutionRequest(
                    invocationId.toString(), agentId, profile.profileId(), prompt,
                    profile.model().modelProfileRef(), required(input, "input"), toolIds,
                    new ExecutionLimits(
                            profile.budget().maxTurns(), profile.budget().maxModelCalls(),
                            profile.budget().maxToolCalls(), profile.budget().maxTaskTokens(),
                            profile.terminationPolicy().noProgressRounds(), deadline),
                    ExecutionSession.specialist(agentId, task, 1, false),
                    Map.of("runId", runId, "incidentId", required(incidentId, "incidentId"),
                            "a2aTaskId", task),
                    CancellationToken.never()));
        }
        usage.append(run, null, new UsageRecord(
                invocationId, incidentId, task, agentId,
                result.usage().inputTokens(), result.usage().outputTokens(),
                result.usage().cachedTokens(), null, false, null, "PROVIDER", null,
                result.outcome().name().equals("COMPLETED") ? "SUCCEEDED" : "FAILED",
                result.completedAt()));
        if (!result.outcome().name().equals("COMPLETED")) {
            throw new IllegalStateException(result.terminationReason());
        }
        Set<String> actualToolCalls = new LinkedHashSet<>();
        result.events().stream().filter(event -> "TOOL_COMPLETED".equals(event.eventType()))
                .map(event -> event.attributes().get("toolName"))
                .filter(Objects::nonNull).forEach(actualToolCalls::add);
        if (!toolIds.isEmpty() && !actualToolCalls.containsAll(toolIds)) {
            throw new IllegalStateException("PHASE7_REQUIRED_TOOL_NOT_EXECUTED");
        }
        return JSON.writeValueAsString(Map.of(
                "schemaVersion", "1.0.0",
                "agentId", agentId,
                "runId", runId,
                "a2aTaskId", task,
                "agentScopeSessionId", result.checkpoint().sessionId(),
                "checkpointId", result.checkpoint().checkpointId(),
                "content", result.decision().summary(),
                "toolCalls", List.copyOf(actualToolCalls),
                "usage", Map.of(
                        "rounds", result.usage().rounds(),
                        "modelCalls", result.usage().modelCalls(),
                        "toolCalls", result.usage().toolCalls(),
                        "inputTokens", result.usage().inputTokens(),
                        "outputTokens", result.usage().outputTokens())));
    }

    @Override
    public void close() {
        // Per-invocation AgentScope runtimes own and close their virtual-thread executors.
    }

    private static String loadPrompt(String templateId) throws IOException {
        String resource = "agent-profiles/prompts/" + templateId + ".md";
        try (InputStream input = Phase7AgentExecutor.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("PROMPT_RESOURCE_MISSING:" + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String processAgentId(AgentProfile.AgentRole role) {
        return switch (role) {
            case SUPERVISOR -> "supervisor";
            case EVIDENCE_COLLECTOR -> "evidence-collector";
            case CODE_ANALYSIS -> "code-analysis";
            case KNOWLEDGE -> "knowledge";
            case DIAGNOSIS -> "diagnosis";
            case REMEDIATION -> "remediation";
        };
    }

    static Set<ModelCapability> providerCapabilities(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return profile.model().requiredCapabilities().stream()
                .map(capability -> ModelCapability.valueOf(capability.name()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String outputContract() {
        String common = " Return one concise JSON object only. Do not reveal hidden reasoning, "
                + "secrets, ground truth, or full model responses.";
        return switch (agentId) {
            case "diagnosis" -> "Select and verify 2 to 4 hypotheses from current-run evidence. "
                    + "Use only these rootCauseCode values when supported: "
                    + "dependency.latency.inventory, database.pool.exhausted.order, "
                    + "service.instance.stopped.inventory. The rootCause title must exactly equal "
                    + "one hypothesis title, and every referenced evidence code must occur in the input. "
                    + "Select dependency.latency.inventory only when both "
                    + "trace.order.inventory_span_latency_high and metric.gateway.request_latency_high exist; "
                    + "select database.pool.exhausted.order only when metric.order.hikari_active_at_max, "
                    + "metric.order.hikari_pending_positive, and log.order.connection_timeout exist; "
                    + "select service.instance.stopped.inventory only when health.inventory.unreachable "
                    + "and log.order.inventory_connection_failed exist. "
                    + "Return exactly {\"outcome\":\"CONCLUSIVE\"|\"PARTIAL\"|\"INCONCLUSIVE\","
                    + "\"rootCause\":null|{\"rootCauseCode\":string,\"title\":string,"
                    + "\"component\":string,\"confidence\":number,"
                    + "\"supportingEvidenceCodes\":[string],\"conflictingEvidenceCodes\":[string]},"
                    + "\"hypotheses\":[{\"title\":string,\"status\":\"SUPPORTED\"|\"CONFLICTED\"|"
                    + "\"REJECTED\",\"confidence\":number,\"supportingEvidenceCodes\":[string],"
                    + "\"conflictingEvidenceCodes\":[string],\"verification\":string}],"
                    + "\"missingEvidenceCodes\":[string],\"limitations\":[string]}. "
                    + "Every hypothesis must reference at least one current-run evidence code. "
                    + "When knowledge outcome is KB_EMPTY or NO_MATCH, or historyLookupApplied is true "
                    + "with historyResultCount zero, missingEvidenceCodes must include that missing "
                    + "knowledge corroboration even when field evidence is conclusive. "
                    + "For a REJECTED hypothesis, put the current evidence that contradicts it in "
                    + "conflictingEvidenceCodes. Absence of evidence belongs only in "
                    + "missingEvidenceCodes and cannot by itself justify REJECTED." + common;
            case "remediation" -> "Return exactly {\"actions\":[string,string,string],"
                    + "\"limitations\":[string]}; actions must be exact controlled action codes in "
                    + "immediate, long-term, monitoring order. Allowed codes are "
                    + "release.leaked.connections, fix.connection.lifecycle, add.hikari.pending.alert, "
                    + "inspect.downstream.span, configure.client.timeout, add.downstream.latency.alert, "
                    + "restore.inventory.instance, verify.health.probes, add.instance.availability.alert. "
                    + "Select only codes supported by the diagnosis and current evidence."
                    + common;
            case "supervisor" -> "Synthesize only the professional artifacts. Return exactly "
                    + "{\"summary\":string,\"outcome\":\"CONCLUSIVE\"|\"PARTIAL\"|"
                    + "\"INCONCLUSIVE\"}." + common;
            default -> "Return exactly {\"summary\":string,\"facts\":[string],"
                    + "\"limitations\":[string]}." + common;
        };
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record ConfiguredModelChatPort(String modelId, ChatPort delegate) implements ChatPort {
        private ConfiguredModelChatPort {
            required(modelId, "modelId");
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            return delegate.complete(withConfiguredModel(request));
        }

        @Override
        public ProviderResult<ChatResponse> invoke(ChatInvocation invocation) {
            return delegate.invoke(new ChatInvocation(
                    withConfiguredModel(invocation.request()), invocation.deadline(), invocation.identity()));
        }

        private ChatRequest withConfiguredModel(ChatRequest request) {
            return new ChatRequest(modelId, request.messages(), request.tools(), request.structuredOutput());
        }
    }
}
