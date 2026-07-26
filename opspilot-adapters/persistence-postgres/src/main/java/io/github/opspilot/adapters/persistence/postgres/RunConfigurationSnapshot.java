package io.github.opspilot.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, secret-free model and knowledge configuration captured when a run is created. */
public record RunConfigurationSnapshot(
        String modelConfigurationVersion,
        String knowledgeConfigurationVersion,
        JsonNode effectiveModelConfiguration,
        JsonNode effectiveKnowledgeConfiguration) {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "secret", "secretvalue", "password", "accesstoken",
            "bearertoken", "credential", "credentials", "privatekey");

    public RunConfigurationSnapshot {
        modelConfigurationVersion = requireText(modelConfigurationVersion, "modelConfigurationVersion");
        knowledgeConfigurationVersion = requireText(
                knowledgeConfigurationVersion, "knowledgeConfigurationVersion");
        effectiveModelConfiguration = validateAndCopy(
                effectiveModelConfiguration, "effectiveModelConfiguration");
        effectiveKnowledgeConfiguration = validateAndCopy(
                effectiveKnowledgeConfiguration, "effectiveKnowledgeConfiguration");
    }

    public static RunConfigurationSnapshot legacy() {
        JsonNode legacy = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", "1.0.0")
                .put("legacy", true);
        return new RunConfigurationSnapshot("legacy", "legacy", legacy, legacy);
    }

    @Override
    public JsonNode effectiveModelConfiguration() {
        return effectiveModelConfiguration.deepCopy();
    }

    @Override
    public JsonNode effectiveKnowledgeConfiguration() {
        return effectiveKnowledgeConfiguration.deepCopy();
    }

    private static JsonNode validateAndCopy(JsonNode value, String field) {
        if (value == null || value.getNodeType() != JsonNodeType.OBJECT) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        JsonNode schemaVersion = value.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isTextual() || schemaVersion.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must contain a textual schemaVersion");
        }
        rejectSensitiveKeys(value, field);
        return value.deepCopy();
    }

    private static void rejectSensitiveKeys(JsonNode value, String path) {
        if (value.isObject()) {
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                String normalized = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (SENSITIVE_KEYS.contains(normalized)) {
                    throw new IllegalArgumentException("RUN_CONFIGURATION_CONTAINS_SECRET:" + path + "." + field.getKey());
                }
                rejectSensitiveKeys(field.getValue(), path + "." + field.getKey());
            }
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                rejectSensitiveKeys(value.get(index), path + "[" + index + "]");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
