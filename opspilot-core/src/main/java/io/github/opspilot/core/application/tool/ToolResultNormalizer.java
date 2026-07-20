package io.github.opspilot.core.application.tool;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.agent.ToolPort.ToolResult;
import io.github.opspilot.core.port.agent.ToolPort.ToolStatus;
import io.github.opspilot.core.port.artifact.ArtifactPort;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Bounds Tool DTOs and externalizes raw bodies through the real Artifact Port. */
public final class ToolResultNormalizer {
    private final ArtifactPort artifacts;
    private final int maxInlineCharacters;

    public ToolResultNormalizer(ArtifactPort artifacts, int maxInlineCharacters) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        if (maxInlineCharacters < 1 || maxInlineCharacters > 512) {
            throw new IllegalArgumentException("inline Tool limit must be between 1 and 512");
        }
        this.maxInlineCharacters = maxInlineCharacters;
    }

    public ToolResult normalize(RunId runId, ToolStatus status, String raw, String errorCode) {
        String redacted = redact(raw == null ? "" : raw);
        if (redacted.length() <= maxInlineCharacters) {
            return new ToolResult(status, redacted, List.of(), List.of(), errorCode);
        }
        ArtifactId artifactId = artifacts.store(runId, "text/plain", redacted.getBytes(StandardCharsets.UTF_8));
        return new ToolResult(status, redacted.substring(0, maxInlineCharacters),
                List.of(artifactId), List.of(), errorCode);
    }

    private static String redact(String value) {
        return value.replaceAll("(?i)(secret|password|token)=[^\\s]+", "$1=[REDACTED]");
    }
}
