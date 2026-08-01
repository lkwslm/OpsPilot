package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Phase7DiagnosisNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void canonicalizesOnlyExactCurrentRunEvidenceIds() throws Exception {
        String firstId = "9ac4e16c-6ee9-4108-93c0-e408dba43fc5";
        String secondId = "6b44fafb-d524-42f8-8a68-97e57113fc64";
        String evidence = """
                {"evidence":[
                  {"evidenceId":"%s","evidenceCode":"metric.order.hikari_active_at_max"},
                  {"evidenceId":"%s","evidenceCode":"log.order.connection_timeout"}
                ]}
                """.formatted(firstId, secondId);
        String input = JSON.writeValueAsString(java.util.Map.of(
                "priorArtifacts", java.util.Map.of("evidence-collector", evidence)));
        String content = """
                {"rootCause":{"supportingEvidenceCodes":["%s","log.order.connection_timeout"],
                 "conflictingEvidenceCodes":[]},"hypotheses":[
                  {"supportingEvidenceCodes":["%s"],"conflictingEvidenceCodes":["unknown.code"]}
                ]}
                """.formatted(firstId, secondId);
        String artifact = JSON.writeValueAsString(java.util.Map.of("content", content));

        JsonNode envelope = JSON.readTree(
                Phase7DiagnosisNormalizer.normalize("diagnosis", input, artifact));
        JsonNode normalized = JSON.readTree(envelope.path("content").asText());

        assertEquals("metric.order.hikari_active_at_max",
                normalized.path("rootCause").path("supportingEvidenceCodes").get(0).asText());
        assertEquals("log.order.connection_timeout",
                normalized.path("hypotheses").get(0).path("supportingEvidenceCodes").get(0).asText());
        assertEquals("unknown.code",
                normalized.path("hypotheses").get(0).path("conflictingEvidenceCodes").get(0).asText());
    }
}
