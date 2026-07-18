package io.github.opspilot.a2a.contract;

/** Persisted artifact metadata and payload used by the interoperability spike. */
public record A2aArtifact(
        String artifactId,
        String mediaType,
        String schemaVersion,
        String sha256,
        String payload) {
}
