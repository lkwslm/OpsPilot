package io.github.opspilot.a2a.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Accepts business success only from a complete, integrity-protected Artifact. */
public final class A2aResultReceiver {

    public A2aArtifact receive(A2aTask task) {
        if (task.state() != A2aTaskState.COMPLETED || task.artifact() == null) {
            throw new A2aProtocolException(409, "A2A_FINAL_ARTIFACT_REQUIRED");
        }
        A2aArtifact artifact = task.artifact();
        if (!task.taskId().equals(artifact.taskId())
                || artifact.runId() == null || artifact.runId().isBlank()
                || artifact.ownerAgentId() == null || artifact.ownerAgentId().isBlank()
                || artifact.mediaType() == null || artifact.mediaType().isBlank()
                || artifact.schemaVersion() == null || artifact.schemaVersion().isBlank()
                || !sha256(artifact.payload()).equals(artifact.sha256())) {
            throw new A2aProtocolException(400, "A2A_FINAL_ARTIFACT_INVALID");
        }
        return artifact;
    }

    public void audit(A2aStatusMessage message) {
        if (message == null || message.taskId() == null || message.kind() == null) {
            throw new IllegalArgumentException("status message must be complete");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
