package io.github.opspilot.core.port.code;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import java.time.Instant;
import java.util.List;

public final class CodeContracts {
    private CodeContracts() {
    }

    /** Immutable, materialized code snapshot. No credential or host filesystem handle crosses this boundary. */
    public record CodeSnapshot(
            String repositoryId,
            String revision,
            String contentSha256,
            ArtifactId manifestArtifactId,
            String readOnlyWorkspaceRef,
            Instant materializedAt) {
        public CodeSnapshot {
            if (!readOnlyWorkspaceRef.startsWith("artifact://")) {
                throw new IllegalArgumentException("workspace must be a controlled read-only artifact reference");
            }
        }
    }

    public record CodeFinding(
            String findingId,
            String ruleId,
            String summary,
            String relativePath,
            String fileSha256,
            List<ArtifactId> artifactIds) {
        public CodeFinding { artifactIds = List.copyOf(artifactIds); }
    }
}
