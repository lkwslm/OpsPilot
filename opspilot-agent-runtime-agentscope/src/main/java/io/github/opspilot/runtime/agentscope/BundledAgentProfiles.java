package io.github.opspilot.runtime.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.opspilot.core.application.profile.AgentProfile;
import io.github.opspilot.core.application.profile.AgentProfileRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads the six explicit built-in profile resources; no classpath scanning is used. */
public final class BundledAgentProfiles {
    private static final List<String> RESOURCES = List.of(
            "agent-profiles/supervisor.json",
            "agent-profiles/evidence-collector.json",
            "agent-profiles/code-analysis.json",
            "agent-profiles/knowledge.json",
            "agent-profiles/diagnosis.json",
            "agent-profiles/remediation.json");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "baseurl", "providerurl", "secret", "secretvalue", "password",
            "accesstoken", "bearertoken", "credential", "credentials", "privatekey");

    private final ObjectMapper json;
    private final ClassLoader resources;

    public BundledAgentProfiles() {
        this(new ObjectMapper().findAndRegisterModules(), BundledAgentProfiles.class.getClassLoader());
    }

    BundledAgentProfiles(ObjectMapper json, ClassLoader resources) {
        this.json = Objects.requireNonNull(json, "json");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public List<AgentProfile> loadAll() {
        List<AgentProfile> profiles = new ArrayList<>();
        for (String resource : RESOURCES) profiles.add(load(resource));
        return List.copyOf(profiles);
    }

    public AgentProfileRegistry registerAll() {
        AgentProfileRegistry registry = new AgentProfileRegistry();
        loadAll().forEach(registry::register);
        return registry;
    }

    private AgentProfile load(String resource) {
        try (InputStream input = resources.getResourceAsStream(resource)) {
            if (input == null) throw new ProfileLoadException("PROFILE_RESOURCE_MISSING", resource);
            return loadDocument(input, resource);
        } catch (ProfileLoadException exception) {
            throw exception;
        } catch (UnrecognizedPropertyException exception) {
            throw new ProfileLoadException("PROFILE_FIELD_UNKNOWN", "$.'" + exception.getPropertyName() + "'");
        } catch (JsonProcessingException exception) {
            throw new ProfileLoadException("PROFILE_PARSE_FAILED", resource);
        } catch (IOException exception) {
            throw new ProfileLoadException("PROFILE_RESOURCE_READ_FAILED", resource);
        } catch (IllegalArgumentException exception) {
            throw new ProfileLoadException("PROFILE_POLICY_INVALID", safeField(exception.getMessage(), resource));
        }
    }

    AgentProfile loadDocument(InputStream input, String resource) {
        try {
            JsonNode node = json.readTree(input);
            rejectSensitiveKeys(node, "$", resource);
            AgentProfile profile = json.treeToValue(node, AgentProfile.class);
            requirePrompt(profile.prompt().templateId(), resource);
            requirePrompt(profile.prompt().systemPolicyTemplateId(), resource);
            return profile;
        } catch (ProfileLoadException exception) {
            throw exception;
        } catch (UnrecognizedPropertyException exception) {
            throw new ProfileLoadException("PROFILE_FIELD_UNKNOWN", "$.'" + exception.getPropertyName() + "'");
        } catch (JsonProcessingException exception) {
            throw new ProfileLoadException("PROFILE_PARSE_FAILED", resource);
        } catch (IOException exception) {
            throw new ProfileLoadException("PROFILE_RESOURCE_READ_FAILED", resource);
        } catch (IllegalArgumentException exception) {
            throw new ProfileLoadException("PROFILE_POLICY_INVALID", safeField(exception.getMessage(), resource));
        }
    }

    private void requirePrompt(String templateId, String profileResource) {
        String prompt = "agent-profiles/prompts/" + templateId + ".md";
        if (resources.getResource(prompt) == null) {
            throw new ProfileLoadException("PROMPT_RESOURCE_MISSING", profileResource + ":prompt.templateId");
        }
    }

    private static void rejectSensitiveKeys(JsonNode node, String path, String resource) {
        if (node == null) return;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String normalized = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                String fieldPath = path + "." + field.getKey();
                if (SENSITIVE_KEYS.contains(normalized)) {
                    throw new ProfileLoadException("PROFILE_SENSITIVE_FIELD", resource + ":" + fieldPath);
                }
                rejectSensitiveKeys(field.getValue(), fieldPath, resource);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectSensitiveKeys(node.get(index), path + "[" + index + "]", resource);
            }
        }
    }

    private static String safeField(String message, String fallback) {
        if (message != null && message.startsWith("AGENT_PROFILE_INVALID:")) {
            return message.substring("AGENT_PROFILE_INVALID:".length());
        }
        return fallback;
    }

    public static final class ProfileLoadException extends RuntimeException {
        private final String code;
        private final String fieldPath;

        ProfileLoadException(String code, String fieldPath) {
            super(code + ":" + fieldPath);
            this.code = code;
            this.fieldPath = fieldPath;
        }

        public String code() { return code; }
        public String fieldPath() { return fieldPath; }
    }
}
