package io.github.opspilot.tools.defaults;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit tool composition registry with exact-major validation and immutable membership. */
public final class ToolRegistry {
    private final Map<String, ToolCapability> tools = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(ToolCapability tool) {
        ensureMutable();
        Objects.requireNonNull(tool, "tool");
        String id = tool.descriptor().toolId();
        if (tools.putIfAbsent(id, tool) != null) throw new ToolRegistryException("DUPLICATE_TOOL_ID", id);
    }

    public synchronized void freeze(Map<String, Integer> requiredContractMajors) {
        ensureMutable();
        for (Map.Entry<String, Integer> required : requiredContractMajors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            ToolCapability actual = tools.get(required.getKey());
            if (actual == null) throw new ToolRegistryException("REQUIRED_TOOL_MISSING", required.getKey());
            if (actual.descriptor().contractMajor() != required.getValue()) {
                throw new ToolRegistryException("TOOL_CONTRACT_MAJOR_INCOMPATIBLE", required.getKey());
            }
        }
        frozen = true;
    }

    public synchronized ToolCapability require(String id, int contractMajor) {
        if (!frozen) throw new ToolRegistryException("TOOL_REGISTRY_NOT_FROZEN", id);
        ToolCapability tool = tools.get(id);
        if (tool == null || tool.descriptor().contractMajor() != contractMajor) {
            throw new ToolRegistryException("TOOL_NOT_AVAILABLE", id);
        }
        return tool;
    }

    public synchronized Set<String> toolIds() {
        if (!frozen) throw new ToolRegistryException("TOOL_REGISTRY_NOT_FROZEN", "tools");
        return Set.copyOf(tools.keySet());
    }

    public synchronized boolean isFrozen() { return frozen; }

    public static ToolRegistry builtIns(
            Collection<? extends ObservabilityQueryTool> observability,
            ControlledTool code, ControlledTool knowledge, ControlledTool sandbox) {
        ToolRegistry registry = new ToolRegistry();
        observability.forEach(registry::register);
        registry.register(code);
        registry.register(knowledge);
        registry.register(sandbox);
        return registry;
    }

    private void ensureMutable() {
        if (frozen) throw new ToolRegistryException("TOOL_REGISTRY_FROZEN", "tools");
    }

    public static final class ToolRegistryException extends RuntimeException {
        private final String code;
        private final String toolId;

        ToolRegistryException(String code, String toolId) {
            super(code + ":" + toolId);
            this.code = code;
            this.toolId = toolId;
        }

        public String code() { return code; }
        public String toolId() { return toolId; }
    }
}
