package io.github.opspilot.core.application.provider;

import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministic, non-sensitive report of validated provider capabilities. */
public record ProviderCapabilityReport(String configVersion, List<ReportEntry> entries) {
    public ProviderCapabilityReport {
        configVersion = requireText(configVersion, "configVersion");
        entries = Objects.requireNonNull(entries, "entries").stream()
                .sorted(Comparator.comparing(entry -> entry.key().canonicalValue()))
                .toList();
    }

    public enum CapabilityStatus {
        VALIDATED,
        UNAVAILABLE
    }

    public record ReportEntry(
            ProviderCapabilityKey key,
            String logicalProfileRef,
            URI baseUrl,
            String secretRef,
            int contextWindowTokens,
            Set<ModelCapability> declaredCapabilities,
            CapabilityStatus status) {
        public ReportEntry {
            Objects.requireNonNull(key, "key");
            logicalProfileRef = requireText(logicalProfileRef, "logicalProfileRef");
            Objects.requireNonNull(baseUrl, "baseUrl");
            secretRef = requireText(secretRef, "secretRef");
            if (contextWindowTokens <= 0) {
                throw new IllegalArgumentException("contextWindowTokens must be positive");
            }
            declaredCapabilities = Set.copyOf(Objects.requireNonNull(declaredCapabilities, "declaredCapabilities"));
            Objects.requireNonNull(status, "status");
        }

        String canonicalValue() {
            String capabilities = declaredCapabilities.stream().map(Enum::name).sorted()
                    .collect(Collectors.joining(","));
            return key.canonicalValue() + "|" + logicalProfileRef + "|" + baseUrl + "|" + secretRef
                    + "|" + contextWindowTokens + "|" + capabilities + "|" + status;
        }
    }

    public String canonicalPayload() {
        return "schemaVersion=1.0.0\nconfigVersion=" + configVersion + "\n"
                + entries.stream().map(ReportEntry::canonicalValue).collect(Collectors.joining("\n"));
    }

    public String digest() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPayload().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
