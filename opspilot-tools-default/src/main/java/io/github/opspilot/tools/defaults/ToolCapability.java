package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.profile.AgentProfile.Permission;

/** Stable metadata shared by every built-in tool. */
public interface ToolCapability {
    ToolDescriptor descriptor();

    record ToolDescriptor(
            String toolId, int contractMajor, String inputSchemaId,
            String outputSchemaId, Permission permission) {
        public ToolDescriptor {
            if (toolId == null || toolId.isBlank()) throw new IllegalArgumentException("toolId must not be blank");
            if (contractMajor < 1) throw new IllegalArgumentException("contractMajor must be positive");
            if (inputSchemaId == null || !inputSchemaId.startsWith("https://opspilot.local/schemas/")) {
                throw new IllegalArgumentException("inputSchemaId must be an OpsPilot schema");
            }
            if (outputSchemaId == null || !outputSchemaId.startsWith("https://opspilot.local/schemas/")) {
                throw new IllegalArgumentException("outputSchemaId must be an OpsPilot schema");
            }
            if (permission == null) throw new IllegalArgumentException("permission must not be null");
        }
    }
}
