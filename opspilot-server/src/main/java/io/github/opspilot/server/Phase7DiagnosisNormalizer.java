package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Canonicalizes exact current-run Evidence IDs to their corresponding Evidence Codes. */
final class Phase7DiagnosisNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private Phase7DiagnosisNormalizer() {
    }

    static String normalize(String agentId, String input, String artifact) throws Exception {
        if (!"diagnosis".equals(agentId)) return artifact;

        JsonNode request = JSON.readTree(input);
        JsonNode evidenceArtifact = JSON.readTree(
                request.path("priorArtifacts").path("evidence-collector").asText());
        Map<String, String> canonicalReferences = new LinkedHashMap<>();
        for (JsonNode evidence : evidenceArtifact.path("evidence")) {
            String id = evidence.path("evidenceId").asText();
            String code = evidence.path("evidenceCode").asText();
            if (!id.isBlank() && !code.isBlank()) {
                canonicalReferences.put(id, code);
                canonicalReferences.put(code, code);
            }
        }
        if (canonicalReferences.isEmpty()) return artifact;

        ObjectNode envelope = (ObjectNode) JSON.readTree(artifact);
        ObjectNode content = (ObjectNode) JSON.readTree(envelope.path("content").asText());
        JsonNode rootCause = content.path("rootCause");
        if (rootCause.isObject()) canonicalize((ObjectNode) rootCause, canonicalReferences);
        for (JsonNode hypothesis : content.path("hypotheses")) {
            if (hypothesis.isObject()) canonicalize((ObjectNode) hypothesis, canonicalReferences);
        }
        envelope.put("content", JSON.writeValueAsString(content));
        return JSON.writeValueAsString(envelope);
    }

    private static void canonicalize(ObjectNode value, Map<String, String> canonicalReferences) {
        canonicalize(value, "supportingEvidenceCodes", canonicalReferences);
        canonicalize(value, "conflictingEvidenceCodes", canonicalReferences);
    }

    private static void canonicalize(
            ObjectNode value, String field, Map<String, String> canonicalReferences) {
        JsonNode references = value.path(field);
        if (!references.isArray()) return;
        var canonical = new LinkedHashSet<String>();
        references.forEach(reference -> {
            String supplied = reference.asText();
            canonical.add(canonicalReferences.getOrDefault(supplied, supplied));
        });
        ArrayNode replacement = JSON.createArrayNode();
        canonical.forEach(replacement::add);
        value.set(field, replacement);
    }
}
