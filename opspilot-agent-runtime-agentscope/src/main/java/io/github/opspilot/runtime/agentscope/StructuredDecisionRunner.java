package io.github.opspilot.runtime.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.DecisionSummaryStore;
import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import io.github.opspilot.core.port.agent.ToolPort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Executes one structured tool decision and allows at most one result repair. */
public final class StructuredDecisionRunner {

    public static final String RESULT_INVALID = "STRUCTURED_OUTPUT_INVALID";
    public static final String DECISION_INVALID = "DECISION_SCHEMA_INVALID";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentScopeChatModelAdapter model;
    private final Toolkit toolkit;
    private final DecisionSummaryStore summaryStore;
    private final AgentScopeAuditCollector audit;

    public StructuredDecisionRunner(
            String modelId,
            ChatPort chatPort,
            List<ToolPort> toolPorts,
            DecisionSummaryStore summaryStore) {
        this(modelId, chatPort, toolPorts, summaryStore, event -> { });
    }

    public StructuredDecisionRunner(
            String modelId,
            ChatPort chatPort,
            List<ToolPort> toolPorts,
            DecisionSummaryStore summaryStore,
            RuntimeAuditSink auditSink) {
        this.model = new AgentScopeChatModelAdapter(modelId, chatPort);
        this.toolkit = new Toolkit();
        toolPorts.stream()
                .map(AgentScopeToolAdapter::new)
                .forEach(toolkit::registerAgentTool);
        this.summaryStore = summaryStore;
        this.audit = new AgentScopeAuditCollector(auditSink);
    }

    public RunResult run(String request) {
        return run(request, () -> false);
    }

    public RunResult run(String request, BooleanSupplier cancellationRequested) {
        boolean cancelled = cancellationRequested.getAsBoolean();
        audit.event("CANCEL_SIGNAL", 0, null, null, null, null, cancelled, Map.of());
        if (cancelled) {
            return RunResult.failed("EXTERNAL_CANCELLED", 0);
        }
        List<Msg> conversation = new ArrayList<>();
        conversation.add(Msg.builderForRole(MsgRole.USER).textContent(request).build());
        audit.event("ROUND_STARTED", 1, null, null, null, null, false, Map.of());
        ChatResponse decisionResponse = callModel(conversation, true);
        auditModelUsage(1, decisionResponse);
        List<ToolUseBlock> calls = decisionResponse.getContent().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
        if (calls.size() != 1 || toolkit.getTool(calls.getFirst().getName()) == null) {
            return RunResult.failed(DECISION_INVALID, 0);
        }

        ToolUseBlock call = calls.getFirst();
        String fingerprint = audit.actionFingerprint("CALL_TOOL", call.getName(), call.getInput());
        audit.event("ACTION_SELECTED", 1, fingerprint, null, null, null, false,
                Map.of("toolName", call.getName(), "arguments", call.getInput()));
        audit.event("TOOL_STARTED", 1, fingerprint, null, null, null, false,
                Map.of("toolName", call.getName(), "arguments", call.getInput()));
        ToolResultBlock toolResult = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(call)
                        .input(call.getInput())
                        .build())
                .block();
        if (toolResult == null) {
            return RunResult.failed("TOOL_RESULT_MISSING", 0);
        }
        audit.event("TOOL_COMPLETED", 1, fingerprint, null, null, null, false,
                Map.of("toolName", call.getName(), "state", toolResult.getState().name()));
        audit.event("CHECKPOINT", 1, fingerprint, null, null,
                audit.checkpoint(call.getId() + ":" + toolResult.getState()), false, Map.of());
        conversation.add(Msg.builderForRole(MsgRole.ASSISTANT).content(call).build());
        conversation.add(Msg.builderForRole(MsgRole.TOOL).content(toolResult).build());
        conversation.add(Msg.builderForRole(MsgRole.USER)
                .textContent("Return only a JSON object matching result.schema.json.")
                .build());

        for (int attempt = 0; attempt < 2; attempt++) {
            int round = attempt + 2;
            audit.event("ROUND_STARTED", round, null, null, null, null, false, Map.of());
            ChatResponse resultResponse = callModel(conversation, false);
            auditModelUsage(round, resultResponse);
            String raw = text(resultResponse);
            Validation validation = validateResult(raw);
            if (validation.result() != null) {
                StructuredResult result = validation.result();
                summaryStore.save(new DecisionSummaryStore.DecisionSummary(
                        "CALL_TOOL",
                        call.getName(),
                        result.outcome(),
                        result.summary(),
                        result.evidenceIds()));
                audit.event("CHECKPOINT", round, fingerprint, null, null,
                        audit.checkpoint(call.getId() + ":" + result.outcome()), false,
                        Map.of("outcome", result.outcome()));
                return RunResult.succeeded(result, attempt);
            }
            if (attempt == 0) {
                conversation.add(Msg.builderForRole(MsgRole.ASSISTANT).textContent(raw).build());
                conversation.add(Msg.builderForRole(MsgRole.USER)
                        .textContent("Repair the JSON once. Validation errors: " + validation.errors())
                        .build());
            }
        }
        return RunResult.failed(RESULT_INVALID, 1);
    }

    private void auditModelUsage(int round, ChatResponse response) {
        if (response.getUsage() == null) {
            audit.event("MODEL_COMPLETED", round, null, null, null, null, false, Map.of());
            return;
        }
        audit.event(
                "MODEL_COMPLETED",
                round,
                null,
                response.getUsage().getInputTokens(),
                response.getUsage().getOutputTokens(),
                null,
                false,
                Map.of("cachedTokens", response.getUsage().getCachedTokens()));
    }

    private ChatResponse callModel(List<Msg> conversation, boolean includeTools) {
        ChatResponse response = model.stream(
                        conversation,
                        includeTools ? toolkit.getToolSchemas() : List.of(),
                        GenerateOptions.builder().temperature(0.0).build())
                .blockFirst();
        if (response == null) {
            throw new IllegalStateException("chat model returned no response");
        }
        return response;
    }

    private String text(ChatResponse response) {
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    private Validation validateResult(String raw) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JsonProcessingException error) {
            return new Validation(null, List.of("invalid JSON"));
        }
        if (!node.isObject()) {
            return new Validation(null, List.of("result must be an object"));
        }
        Set<String> allowed = Set.of("schemaVersion", "outcome", "summary", "evidenceIds");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                errors.add("unknown field: " + name);
            }
        });
        String schemaVersion = textValue(node, "schemaVersion", errors);
        String outcome = textValue(node, "outcome", errors);
        String summary = textValue(node, "summary", errors);
        if (!"1.0.0".equals(schemaVersion)) {
            errors.add("schemaVersion must be 1.0.0");
        }
        if (outcome == null || !Set.of("CONCLUSIVE", "PARTIAL", "INCONCLUSIVE").contains(outcome)) {
            errors.add("outcome is not allowed");
        }
        if (summary != null && (summary.isBlank() || summary.length() > 2000)) {
            errors.add("summary length is invalid");
        }
        List<String> evidenceIds = new ArrayList<>();
        JsonNode evidence = node.get("evidenceIds");
        if (evidence == null || !evidence.isArray() || evidence.size() > 100) {
            errors.add("evidenceIds must be an array with at most 100 items");
        } else {
            evidence.forEach(item -> {
                if (!item.isTextual() || !isUuid(item.textValue())) {
                    errors.add("evidenceIds must contain UUID values");
                } else {
                    evidenceIds.add(item.textValue());
                }
            });
            if (new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
                errors.add("evidenceIds must be unique");
            }
        }
        return errors.isEmpty()
                ? new Validation(new StructuredResult(outcome, summary, evidenceIds), List.of())
                : new Validation(null, List.copyOf(errors));
    }

    private String textValue(JsonNode node, String field, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            errors.add(field + " must be a string");
            return null;
        }
        return value.textValue();
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public record StructuredResult(String outcome, String summary, List<String> evidenceIds) {
        public StructuredResult {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    public record RunResult(
            boolean success,
            String reasonCode,
            StructuredResult result,
            int repairAttempts) {

        static RunResult succeeded(StructuredResult result, int repairs) {
            return new RunResult(true, null, result, repairs);
        }

        static RunResult failed(String reasonCode, int repairs) {
            return new RunResult(false, reasonCode, null, repairs);
        }
    }

    private record Validation(StructuredResult result, List<String> errors) {
    }
}
