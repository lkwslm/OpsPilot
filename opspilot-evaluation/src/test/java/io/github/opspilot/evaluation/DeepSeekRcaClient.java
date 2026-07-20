package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Test-only DeepSeek structured-RCA fixture retained for the archived Phase 0 gate. */
final class DeepSeekRcaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String modelId;

    DeepSeekRcaClient(URI endpoint, String apiKey, String modelId) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.modelId = modelId;
    }

    ModelResult generate(UUID incidentId, UUID runId, UUID evidenceId, UUID artifactId, String claim) {
        try {
            ObjectNode request = JSON.createObjectNode();
            request.put("model", modelId);
            request.put("temperature", 0);
            request.set("response_format", JSON.createObjectNode().put("type", "json_object"));
            var messages = request.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", "Return only one JSON object matching the requested RCA contract. "
                            + "Use only the supplied evidence. Never infer from ground truth or hidden data. "
                            + "This request supplies one log, which is insufficient for a unique cause: "
                            + "set outcome to the exact uppercase enum INCONCLUSIVE and rootCause to JSON null.");
            messages.addObject()
                    .put("role", "user")
                    .put("content", prompt(incidentId, runId, evidenceId, artifactId, claim));

            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new Phase0VerticalSlice.Failure("MODEL_UNAVAILABLE", "provider status " + response.statusCode());
            }
            JsonNode envelope = JSON.readTree(response.body());
            JsonNode contentNode = envelope.at("/choices/0/message/content");
            if (!contentNode.isTextual()) {
                throw new Phase0VerticalSlice.Failure("MODEL_RESPONSE_INVALID", "missing structured content");
            }
            JsonNode rca = JSON.readTree(contentNode.textValue());
            validate(rca, incidentId, runId, evidenceId, artifactId);
            JsonNode usage = envelope.path("usage");
            return new ModelResult(rca, envelope.path("id").asText("unavailable"),
                    usage.path("prompt_tokens").asInt(-1), usage.path("completion_tokens").asInt(-1));
        } catch (Phase0VerticalSlice.Failure failure) {
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Phase0VerticalSlice.Failure("MODEL_CANCELLED", exception.getClass().getSimpleName());
        } catch (Exception exception) {
            throw new Phase0VerticalSlice.Failure("MODEL_UNAVAILABLE", exception.getClass().getSimpleName());
        }
    }

    private static String prompt(UUID incidentId, UUID runId, UUID evidenceId, UUID artifactId, String claim) {
        return "Create RCA schemaVersion 1.0.0 for incidentId=" + incidentId + ", runId=" + runId
                + ". Allowed evidence only: evidenceId=" + evidenceId + ", artifactId=" + artifactId
                + ", evidenceCode=log.database.pool_exhausted, claim=" + claim + ". "
                + "Required keys: schemaVersion,incidentId,runId,summary,severity,outcome,rootCause,"
                + "evidenceAssessment,hypotheses,actions,citations,limitations,generatedAt. "
                + "severity must be exact uppercase HIGH; outcome must be exact uppercase INCONCLUSIVE; rootCause must be null. "
                + "evidenceAssessment has coverage,missingEvidenceCodes,unavailableCapabilities; coverage must be a JSON number from 0 to 1. "
                + "hypotheses must contain at least one object with a new UUID hypothesisId,title,status,confidence,"
                + "supportingEvidenceIds,conflictingEvidenceIds; status must be exactly SUPPORTED, REFUTED, or UNVERIFIED, "
                + "and confidence must be a JSON number from 0 to 1. actions has immediate,longTerm,monitoring,tests,"
                + "humanNextSteps,rollback arrays. Every action array must be [] or contain only objects shaped "
                + "{\"actionCode\":\"code\",\"description\":\"text\",\"approvalRequired\":false}; never put strings in action arrays. "
                + "citations must be an array; each citation has claimId,evidenceId,evidenceCode,artifactId. "
                + "limitations must be a JSON array containing zero or more strings, never a single string. "
                + "All evidence references must equal the allowed evidenceId; all artifact references must equal the allowed artifactId.";
    }

    private static void validate(JsonNode rca, UUID incidentId, UUID runId, UUID evidenceId, UUID artifactId) {
        requireText(rca, "schemaVersion", "1.0.0");
        requireText(rca, "incidentId", incidentId.toString());
        requireText(rca, "runId", runId.toString());
        String outcome = rca.path("outcome").asText();
        if (!(outcome.equals("CONCLUSIVE") || outcome.equals("PARTIAL") || outcome.equals("INCONCLUSIVE"))) {
            throw new Phase0VerticalSlice.Failure("MODEL_RESPONSE_INVALID", "invalid outcome");
        }
        if (outcome.equals("INCONCLUSIVE") && !rca.path("rootCause").isNull()) {
            throw new Phase0VerticalSlice.Failure("MODEL_RESPONSE_INVALID", "inconclusive rootCause must be null");
        }
        if (!rca.path("hypotheses").isArray() || rca.path("hypotheses").isEmpty()
                || !rca.path("actions").isObject() || !rca.path("evidenceAssessment").isObject()) {
            throw new Phase0VerticalSlice.Failure("MODEL_RESPONSE_INVALID", "missing RCA structure");
        }
        walkReferences(rca, evidenceId.toString(), artifactId.toString());
    }

    private static void walkReferences(JsonNode node, String evidenceId, String artifactId) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ((entry.getKey().equals("evidenceId") || entry.getKey().endsWith("EvidenceIds"))
                        && !referencesOnly(entry.getValue(), evidenceId)) {
                    throw new Phase0VerticalSlice.Failure("RCA_EVIDENCE_REFERENCE_FORBIDDEN", entry.getKey());
                }
                if (entry.getKey().equals("artifactId") && !entry.getValue().asText().equals(artifactId)) {
                    throw new Phase0VerticalSlice.Failure("RCA_ARTIFACT_REFERENCE_FORBIDDEN", entry.getKey());
                }
                walkReferences(entry.getValue(), evidenceId, artifactId);
            });
        } else if (node.isArray()) {
            node.forEach(child -> walkReferences(child, evidenceId, artifactId));
        }
    }

    private static boolean referencesOnly(JsonNode node, String allowed) {
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (!value.asText().equals(allowed)) {
                    return false;
                }
            }
            return true;
        }
        return node.asText().equals(allowed);
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) {
            throw new Phase0VerticalSlice.Failure("MODEL_RESPONSE_INVALID", field);
        }
    }

    record ModelResult(JsonNode rca, String providerRequestId, int promptTokens, int completionTokens) {
    }
}
