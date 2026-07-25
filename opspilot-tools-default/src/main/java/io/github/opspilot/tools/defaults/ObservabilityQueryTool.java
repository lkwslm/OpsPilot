package io.github.opspilot.tools.defaults;

import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolRequest;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolResult;

public interface ObservabilityQueryTool {
    String name();
    String inputSchemaVersion();
    ToolResult execute(ToolRequest request);
}
