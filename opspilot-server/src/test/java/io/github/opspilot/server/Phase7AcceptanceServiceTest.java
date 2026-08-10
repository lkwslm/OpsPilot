package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7AcceptanceServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void derivesMissingEvidenceFromEachKnowledgeEmptyOutcome() throws Exception {
        assertEquals("knowledge.catalog.empty", Phase7AcceptanceService.emptyOutcomeMissingEvidence(
                JSON.readTree("{\"outcome\":\"KB_EMPTY\"}")));
        assertEquals("knowledge.query.no_match", Phase7AcceptanceService.emptyOutcomeMissingEvidence(
                JSON.readTree("{\"outcome\":\"NO_MATCH\"}")));
        assertEquals("knowledge.history.insufficient", Phase7AcceptanceService.emptyOutcomeMissingEvidence(
                JSON.readTree("{\"outcome\":\"MATCH\",\"historyLookupApplied\":true,"
                        + "\"historyResultCount\":0}")));
        assertNull(Phase7AcceptanceService.emptyOutcomeMissingEvidence(
                JSON.readTree("{\"outcome\":\"MATCH\",\"historyLookupApplied\":false}")));
    }

    @Test
    void mergesDiagnosisAndRemediationLimitationsWithoutDuplicates() {
        assertEquals(
                List.of("trace unavailable", "knowledge unavailable", "pool metrics unavailable"),
                Phase7AcceptanceService.mergeDistinctStrings(
                        List.of("trace unavailable", "knowledge unavailable"),
                        List.of("pool metrics unavailable", "knowledge unavailable")));
    }

    @Test
    void acceptsCanonicalRawArtifactDigestAndRejectsBareOrMalformedValues() {
        String digest = "a".repeat(64);

        assertTrue(Phase7AcceptanceService.validRawArtifactSha256("sha256:" + digest));
        assertFalse(Phase7AcceptanceService.validRawArtifactSha256(digest));
        assertFalse(Phase7AcceptanceService.validRawArtifactSha256("sha256:" + "g".repeat(64)));
    }
}
