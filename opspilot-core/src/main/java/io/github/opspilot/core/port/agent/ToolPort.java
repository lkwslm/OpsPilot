package io.github.opspilot.core.port.agent;

import java.util.Map;

/** Project-owned boundary for tools exposed to an agent runtime. */
public interface ToolPort {

    String name();

    String description();

    Map<String, Object> inputSchema();

    ToolResult execute(Map<String, Object> input);

    record ToolResult(boolean success, String content) {
    }
}
