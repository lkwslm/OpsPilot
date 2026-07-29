package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.profile.AgentProfile.Permission;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolResult;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolStatus;

import java.util.List;
import java.util.Objects;

/** Thin adapter for the three non-observability built-in capabilities. */
public final class ControlledTool implements ToolCapability {
    private static final String SCHEMA = "https://opspilot.local/schemas/tools/1.0.0";
    private final ToolDescriptor descriptor;
    private final Executor executor;

    private ControlledTool(String toolId, String requestDefinition, Permission permission, Executor executor) {
        this.descriptor = new ToolDescriptor(toolId, 1, SCHEMA + "#/$defs/" + requestDefinition,
                SCHEMA + "#/$defs/result", permission);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static ControlledTool codeSearch(Executor executor) {
        return new ControlledTool("CodeSearchTool", "codeRequest", Permission.READ_ONLY, executor);
    }

    public static ControlledTool knowledgeSearch(Executor executor) {
        return new ControlledTool("KnowledgeSearchTool", "knowledgeRequest", Permission.READ_ONLY, executor);
    }

    public static ControlledTool sandboxTest(Executor executor) {
        return new ControlledTool("SandboxTestTool", "sandboxRequest", Permission.CONTROLLED_EXECUTION, executor);
    }

    @Override
    public ToolDescriptor descriptor() { return descriptor; }

    public ToolResult execute(Object request) {
        try {
            ToolResult result = executor.execute(request);
            return Objects.requireNonNull(result, "result");
        } catch (DeniedException denied) {
            return result(ToolStatus.DENIED, "Capability request denied", "TOOL_ACCESS_DENIED");
        } catch (RuntimeException failure) {
            return result(ToolStatus.FAILED, "Capability execution failed", "TOOL_EXECUTION_FAILED");
        }
    }

    public static ToolResult result(ToolStatus status, String summary, String code) {
        return new ToolResult(status, summary, List.of(), null, List.of(), List.of(), List.of(), code);
    }

    @FunctionalInterface
    public interface Executor { ToolResult execute(Object request); }
    public static final class DeniedException extends RuntimeException { }
}
