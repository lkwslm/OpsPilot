package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed validator for the JSON Schema subset emitted by OpsPilot tool/profile contracts. */
final class StrictJsonSchemaValidator {
    private static final Set<String> SUPPORTED = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum", "description");
    private final ObjectMapper json;

    StrictJsonSchemaValidator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    JsonNode parseAndValidate(String value, Map<String, Object> schema) {
        JsonNode instance;
        try {
            instance = json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new StructuredOutputException("structured output is not valid JSON");
        }
        validate(instance, json.valueToTree(schema), "$");
        return instance;
    }

    private void validate(JsonNode instance, JsonNode schema, String path) {
        if (!schema.isObject()) {
            throw new StructuredOutputException("schema node must be an object at " + path);
        }
        schema.fieldNames().forEachRemaining(keyword -> {
            if (!SUPPORTED.contains(keyword)) {
                throw new StructuredOutputException("unsupported schema keyword at " + path + ": " + keyword);
            }
        });
        if (schema.has("enum")) {
            boolean matched = false;
            for (JsonNode allowed : schema.path("enum")) {
                matched |= allowed.equals(instance);
            }
            if (!matched) {
                throw new StructuredOutputException("value is outside enum at " + path);
            }
        }
        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> validateObject(instance, schema, path);
            case "array" -> validateArray(instance, schema, path);
            case "string" -> require(instance.isTextual(), path, type);
            case "integer" -> require(instance.isIntegralNumber(), path, type);
            case "number" -> require(instance.isNumber(), path, type);
            case "boolean" -> require(instance.isBoolean(), path, type);
            case "null" -> require(instance.isNull(), path, type);
            case "" -> {
                if (!schema.has("enum")) {
                    throw new StructuredOutputException("schema type is required at " + path);
                }
            }
            default -> throw new StructuredOutputException("unsupported schema type at " + path + ": " + type);
        }
    }

    private void validateObject(JsonNode instance, JsonNode schema, String path) {
        require(instance.isObject(), path, "object");
        JsonNode properties = schema.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) {
            throw new StructuredOutputException("properties must be an object at " + path);
        }
        Set<String> required = new HashSet<>();
        JsonNode requiredNode = schema.path("required");
        if (!requiredNode.isMissingNode()) {
            if (!requiredNode.isArray()) {
                throw new StructuredOutputException("required must be an array at " + path);
            }
            requiredNode.forEach(node -> required.add(node.asText()));
        }
        required.forEach(field -> {
            if (!instance.has(field)) {
                throw new StructuredOutputException("required property is missing at " + path + "." + field);
            }
        });
        boolean allowAdditional = !schema.has("additionalProperties")
                || schema.path("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = instance.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = properties.path(field.getKey());
            if (propertySchema.isMissingNode()) {
                if (!allowAdditional) {
                    throw new StructuredOutputException("additional property is forbidden at " + path);
                }
            } else {
                validate(field.getValue(), propertySchema, path + "." + field.getKey());
            }
        }
    }

    private void validateArray(JsonNode instance, JsonNode schema, String path) {
        require(instance.isArray(), path, "array");
        JsonNode items = schema.path("items");
        if (items.isMissingNode()) {
            throw new StructuredOutputException("array items schema is required at " + path);
        }
        for (int index = 0; index < instance.size(); index++) {
            validate(instance.get(index), items, path + "[" + index + "]");
        }
    }

    private static void require(boolean valid, String path, String type) {
        if (!valid) {
            throw new StructuredOutputException("expected " + type + " at " + path);
        }
    }

    static final class StructuredOutputException extends IllegalArgumentException {
        StructuredOutputException(String message) {
            super(message);
        }
    }
}
