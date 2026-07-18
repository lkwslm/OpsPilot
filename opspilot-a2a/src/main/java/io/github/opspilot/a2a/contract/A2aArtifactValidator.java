package io.github.opspilot.a2a.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Fail-closed validation required before an A2A artifact enters a domain table. */
public final class A2aArtifactValidator {

    public void validate(
            A2aTask task,
            String requestedTaskId,
            String callerAgentId,
            String expectedCallerAgentId,
            Set<String> authorizedAgentIds) {
        A2aArtifact artifact = task.artifact();
        if (artifact == null || !"application/json".equals(artifact.mediaType())) {
            throw new IllegalArgumentException("ARTIFACT_MEDIA_TYPE_UNSUPPORTED");
        }
        if (!artifact.schemaVersion().matches("1(?:\\..*)?")) {
            throw new IllegalArgumentException("ARTIFACT_SCHEMA_MAJOR_UNSUPPORTED");
        }
        if (!sha256(artifact.payload()).equals(artifact.sha256())) {
            throw new IllegalArgumentException("ARTIFACT_SHA256_MISMATCH");
        }
        if (!task.taskId().equals(requestedTaskId)) {
            throw new IllegalArgumentException("ARTIFACT_TASK_ID_MISMATCH");
        }
        if (!expectedCallerAgentId.equals(callerAgentId)) {
            throw new IllegalArgumentException("ARTIFACT_CALLER_IDENTITY_MISMATCH");
        }
        if (!authorizedAgentIds.contains(callerAgentId)) {
            throw new IllegalArgumentException("ARTIFACT_ACCESS_DENIED");
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
