package io.github.opspilot.a2a.contract;

import java.util.List;

/** Reliable final result with ownership, integrity, and reference metadata. */
public record A2aArtifact(
        String artifactId,
        String mediaType,
        String schemaVersion,
        String sha256,
        String payload,
        String taskId,
        String runId,
        String ownerAgentId,
        List<String> references) {

    public A2aArtifact {
        references = List.copyOf(references);
    }

    /** Compatibility constructor for persisted Phase 0 fixtures. */
    public A2aArtifact(
            String artifactId,
            String mediaType,
            String schemaVersion,
            String sha256,
            String payload) {
        this(artifactId, mediaType, schemaVersion, sha256, payload,
                "legacy-task", "legacy-run", "legacy-agent", List.of());
    }
}
