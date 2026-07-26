package io.github.opspilot.core.provider;

import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.application.provider.ModelProfileValidation;
import io.github.opspilot.core.application.provider.ProviderBoundaryRedactor;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.CapabilityStatus;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.ReportEntry;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.CapabilityKind;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProtocolVersion;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelProviderSecurityTest {
    @Test
    void validatesEveryRequiredFieldWithoutEchoingRejectedValues() {
        ModelProfileValidation.EnabledProfileCandidate invalid =
                new ModelProfileValidation.EnabledProfileCandidate(
                        "modelProfiles.diagnosis", "", "http://user:pass@example.test?secret=x",
                        "literal:UNIQUE_SECRET_MARKER", "", 0, 0, 0, 0,
                        Set.of(), Set.of(ModelCapability.TOOL_CALLS));

        ModelProfileValidation.ConfigurationException failure = assertThrows(
                ModelProfileValidation.ConfigurationException.class,
                () -> ModelProfileValidation.validate(invalid));

        assertEquals(List.of(
                "modelProfiles.diagnosis.baseUrl",
                "modelProfiles.diagnosis.capabilities",
                "modelProfiles.diagnosis.concurrencyLimit",
                "modelProfiles.diagnosis.contextWindowTokens",
                "modelProfiles.diagnosis.maxAttempts",
                "modelProfiles.diagnosis.maxOutputTokensPerCall",
                "modelProfiles.diagnosis.model",
                "modelProfiles.diagnosis.provider",
                "modelProfiles.diagnosis.secretRef"),
                failure.errors().stream().map(ModelProfileValidation.ConfigurationError::fieldPath).toList());
        assertFalse(failure.getMessage().contains("UNIQUE_SECRET_MARKER"));
        assertTrue(failure.getMessage().contains("modelProfiles.diagnosis.secretRef"));

        assertDoesNotThrow(() -> ModelProfileValidation.validate(
                new ModelProfileValidation.EnabledProfileCandidate(
                        "modelProfiles.diagnosis", "deepseek", "https://api.deepseek.com", "env:DEEPSEEK_API_KEY",
                        "deepseek-v4-flash", 1_000_000, 3, 2, 3_072,
                        Set.of(ModelCapability.STRUCTURED_OUTPUT, ModelCapability.TOOL_CALLS),
                        Set.of(ModelCapability.STRUCTURED_OUTPUT))));
    }

    @Test
    void capabilityReportIsSecretFreeDeterministicAndOrderIndependent() {
        ReportEntry chat = entry(CapabilityKind.CHAT, "chat", "deepseek", "deepseek-v4-flash");
        ReportEntry embedding = entry(CapabilityKind.EMBEDDING, "embedding", "infinity", "embed-bge-m3");

        ProviderCapabilityReport first = new ProviderCapabilityReport("config-v1", List.of(chat, embedding));
        ProviderCapabilityReport second = new ProviderCapabilityReport("config-v1", List.of(embedding, chat));

        assertEquals(first.digest(), second.digest());
        assertEquals(first.canonicalPayload(), second.canonicalPayload());
        assertFalse(first.canonicalPayload().contains("UNIQUE_SECRET_MARKER"));
        assertTrue(first.digest().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void fixedBoundaryRedactorRemovesStructuredAndExactSecretValues() {
        char[] secret = "UNIQUE_SECRET_MARKER".toCharArray();
        String redacted = ProviderBoundaryRedactor.redact(
                "Authorization: Bearer UNIQUE_SECRET_MARKER api_key=UNIQUE_SECRET_MARKER body=UNIQUE_SECRET_MARKER",
                secret);

        assertFalse(redacted.contains("UNIQUE_SECRET_MARKER"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertEquals("provider call failed", ProviderBoundaryRedactor.failureSummary(
                new IllegalStateException("password=UNIQUE_SECRET_MARKER"), secret));
    }

    private static ReportEntry entry(
            CapabilityKind kind, String capability, String providerId, String model) {
        return new ReportEntry(
                new ProviderCapabilityKey(kind, capability, providerId, "1.0.0",
                        new ProtocolVersion(1, 0), model, "revision-1"),
                capability + "-profile", URI.create("https://provider.example"),
                "env:PROVIDER_API_KEY", 8_192, Set.of(ModelCapability.STRUCTURED_OUTPUT),
                CapabilityStatus.VALIDATED);
    }
}
