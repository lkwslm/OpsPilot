package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort.QueryCommand;
import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;

import java.time.Duration;
import java.util.*;

abstract class AbstractObservabilityQueryTool implements ObservabilityQueryTool {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "url", "uri", "promql", "sql", "dsl", "token", "password", "secret", "connectionref");
    private final String name;
    private final SignalType signal;
    private final Set<String> templates;
    private final Set<String> parameterKeys;
    private final ObservabilityQueryPort queries;
    private final EvidenceNormalizer normalizer;
    private final ToolAuthorizationPort authorization;
    private final ToolAuditSink audit;

    AbstractObservabilityQueryTool(
            String name, SignalType signal, Set<String> templates, Set<String> parameterKeys,
            ObservabilityQueryPort queries, EvidenceNormalizer normalizer,
            ToolAuthorizationPort authorization, ToolAuditSink audit) {
        this.name = name;
        this.signal = signal;
        this.templates = Set.copyOf(templates);
        this.parameterKeys = Set.copyOf(parameterKeys);
        this.queries = queries;
        this.normalizer = normalizer;
        this.authorization = authorization;
        this.audit = audit;
    }

    public final String name() { return name; }
    public final String inputSchemaVersion() { return "1.0.0"; }

    public final ToolResult execute(ToolRequest request) {
        if (!authorized(request)) return result(ToolStatus.DENIED, "Access denied", null, "TOOL_ACCESS_DENIED");
        try {
            validate(request);
            var collection = queries.collect(new QueryCommand(
                    request.targetSystemId(), request.runId(), request.taskId(), request.resource(), signal,
                    request.queryTemplateId(), request.parameters(), request.windowStart(), request.windowEnd(),
                    request.requiredSourceId(), request.explicitMultiSource(), request.allowedResourceIds()));
            if (collection.batches().stream().allMatch(batch -> batch.observations().isEmpty())) {
                ToolResult empty = new ToolResult(ToolStatus.EMPTY, "Source query completed with no records",
                        collection.batches().stream().map(batch -> batch.batchId()).toList(), null,
                        List.of(), List.of(), List.of("NO_RECORDS"), null);
                audit.record(name, request.runId(), empty.status());
                return empty;
            }
            var bundle = normalizer.normalizeRuntime(collection.batches(),
                    new NormalizationContext(request.incidentId(), request.runId(), request.taskId()));
            ToolResult success = new ToolResult(ToolStatus.SUCCEEDED,
                    "Collected " + bundle.evidence().size() + " normalized facts",
                    bundle.observationBatchIds(), bundle.bundleId(),
                    bundle.evidence().stream().map(evidence -> evidence.evidenceId()).toList(),
                    bundle.evidence().stream().flatMap(evidence -> evidence.artifactIds().stream()).distinct().toList(),
                    List.of(), null);
            audit.record(name, request.runId(), success.status());
            return success;
        } catch (ToolInputException invalid) {
            ToolResult denied = result(ToolStatus.DENIED, "Request rejected before Source execution",
                    request.runId(), "TOOL_INPUT_INVALID");
            audit.record(name, request.runId(), denied.status());
            return denied;
        } catch (RuntimeException failure) {
            ToolResult failed = result(ToolStatus.FAILED, "Source or normalization failed",
                    request.runId(), "OBSERVABILITY_QUERY_FAILED");
            audit.record(name, request.runId(), failed.status());
            return failed;
        }
    }

    private boolean authorized(ToolRequest request) {
        if (request == null || request.resource() == null || request.runId() == null) return false;
        boolean allowed = authorization.allowed(request.targetSystemId(), request.runId(), request.resource());
        if (!allowed) audit.record(name, request.runId(), ToolStatus.DENIED);
        return allowed;
    }

    private void validate(ToolRequest request) {
        if (!"1.0.0".equals(request.schemaVersion())
                || !templates.contains(request.queryTemplateId())
                || request.windowStart() == null || request.windowEnd() == null
                || !request.windowEnd().isAfter(request.windowStart())
                || Duration.between(request.windowStart(), request.windowEnd()).compareTo(Duration.ofHours(1)) > 0
                || !request.allowedResourceIds().contains(request.resource().resourceId())
                || !request.targetSystemId().equals(request.resource().systemId())
                || request.parameters().size() > 16
                || !parameterKeys.containsAll(request.parameters().keySet())) {
            throw new ToolInputException();
        }
        request.parameters().forEach((key, value) -> {
            String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (FORBIDDEN_KEYS.stream().anyMatch(normalized::contains)
                    || value instanceof String text && (text.length() > 256
                    || text.matches("(?i).*(https?://|select\\s+|rate\\(|sum\\().*"))) {
                throw new ToolInputException();
            }
        });
    }

    private static ToolResult result(ToolStatus status, String summary, UUID runId, String code) {
        return new ToolResult(status, summary, List.of(), null, List.of(), List.of(), List.of(), code);
    }

    private static final class ToolInputException extends RuntimeException { }
}
