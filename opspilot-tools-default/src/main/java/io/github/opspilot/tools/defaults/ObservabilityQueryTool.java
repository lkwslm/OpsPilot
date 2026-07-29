package io.github.opspilot.tools.defaults;

import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolRequest;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolResult;

public interface ObservabilityQueryTool extends ToolCapability {
    String name();
    String inputSchemaVersion();
    ToolResult execute(ToolRequest request);

    @Override
    default ToolDescriptor descriptor() {
        String schema = "https://opspilot.local/schemas/tools/1.0.0";
        String requestDefinition = switch (name()) {
            case "LogQueryTool" -> "logRequest";
            case "MetricQueryTool" -> "metricRequest";
            case "TraceQueryTool" -> "traceRequest";
            case "HealthQueryTool" -> "healthRequest";
            case "TopologyQueryTool" -> "topologyRequest";
            case "ConfigReadTool" -> "configRequest";
            default -> throw new IllegalStateException("UNKNOWN_BUILT_IN_TOOL:" + name());
        };
        return new ToolDescriptor(name(), 1, schema + "#/$defs/" + requestDefinition,
                schema + "#/$defs/result",
                io.github.opspilot.core.application.profile.AgentProfile.Permission.READ_ONLY);
    }
}
