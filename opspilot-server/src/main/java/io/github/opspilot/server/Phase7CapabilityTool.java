package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.port.agent.ToolPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A bounded AgentScope tool backed by one lazily executed Phase-7 capability call. */
final class Phase7CapabilityTool implements ToolPort {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Map<String, String> SIGNAL_BY_TOOL = Map.of(
            "LogQueryTool", "LOG",
            "MetricQueryTool", "METRIC",
            "TraceQueryTool", "TRACE",
            "HealthQueryTool", "HEALTH",
            "TopologyQueryTool", "TOPOLOGY",
            "ConfigReadTool", "CONFIG");

    private final String name;
    private final LazyResult result;

    Phase7CapabilityTool(String name, LazyResult result) {
        this.name = name;
        this.result = result;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Execute the governed, read-only " + name + " capability for the current Run.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("purpose", Map.of("type", "string", "maxLength", 160)),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        try {
            JsonNode root = JSON.readTree(result.get());
            Set<ArtifactId> artifacts = new LinkedHashSet<>();
            Set<EvidenceId> evidence = new LinkedHashSet<>();
            List<String> facts = new ArrayList<>();
            if (SIGNAL_BY_TOOL.containsKey(name)) {
                String signal = SIGNAL_BY_TOOL.get(name);
                for (JsonNode item : root.path("evidence")) {
                    evidence.add(new EvidenceId(UUID.fromString(item.path("evidenceId").asText())));
                    for (JsonNode artifact : item.path("artifactIds")) {
                        artifacts.add(new ArtifactId(UUID.fromString(artifact.asText())));
                    }
                    if (signal.equals(item.path("signalType").asText())) {
                        facts.add(item.path("evidenceCode").asText());
                    }
                }
            } else if ("CodeSearchTool".equals(name)) {
                for (JsonNode finding : root.path("findings")) {
                    for (JsonNode artifact : finding.path("artifactIds")) {
                        artifacts.add(new ArtifactId(UUID.fromString(artifact.asText())));
                    }
                    facts.add(finding.path("ruleId").asText() + "@"
                            + finding.path("relativePath").asText());
                }
            } else if ("KnowledgeSearchTool".equals(name)) {
                for (JsonNode reference : root.path("references")) {
                    artifacts.add(new ArtifactId(UUID.fromString(reference.path("artifactId").asText())));
                    facts.add(reference.path("location").asText());
                }
            }
            String summary = name + " result=" + (facts.isEmpty() ? "NO_MATCH" : String.join(",", facts));
            if (summary.length() > 512) summary = summary.substring(0, 512);
            return new ToolResult(
                    evidence.isEmpty() && artifacts.isEmpty() ? ToolStatus.EMPTY : ToolStatus.SUCCEEDED,
                    summary, List.copyOf(artifacts), List.copyOf(evidence), null);
        } catch (Exception failure) {
            String errorCode = stableErrorCode(failure);
            return new ToolResult(ToolStatus.FAILED, name + " failed: " + errorCode,
                    List.of(), List.of(), errorCode);
        }
    }

    private static String stableErrorCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.matches("[A-Z][A-Z0-9_]{2,63}")) {
                return message;
            }
            current = current.getCause();
        }
        return "PHASE7_CAPABILITY_FAILED";
    }

    static final class LazyResult {
        private final CheckedSupplier supplier;
        private String value;
        private Exception failure;
        private boolean executed;

        LazyResult(CheckedSupplier supplier) {
            this.supplier = supplier;
        }

        synchronized String get() throws Exception {
            if (!executed) {
                executed = true;
                try {
                    value = supplier.get();
                } catch (Exception exception) {
                    failure = exception;
                }
            }
            if (failure != null) throw failure;
            return value;
        }

        synchronized String requireExecuted() {
            if (!executed || value == null) throw new IllegalStateException("PHASE7_CAPABILITY_NOT_EXECUTED");
            return value;
        }
    }

    @FunctionalInterface
    interface CheckedSupplier {
        String get() throws Exception;
    }
}
