package io.github.opspilot.core.port.code;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CodeContracts {
    private CodeContracts() {
    }

    /** Immutable, materialized code snapshot. No credential or host filesystem handle crosses this boundary. */
    public record CodeSnapshot(
            String snapshotId,
            String sourceId,
            SourceKind sourceKind,
            String adapterId,
            String adapterVersion,
            String repositoryId,
            String commitSha,
            String contentSha256,
            ArtifactId manifestArtifactId,
            String manifestSha256,
            ReadOnlyWorkspaceHandle workspace,
            Map<String, String> fileHashes,
            Instant materializedAt) {
        private static final Pattern FULL_SHA = Pattern.compile("[a-f0-9]{40}");
        public CodeSnapshot {
            requireText(snapshotId, "snapshotId");
            requireText(sourceId, "sourceId");
            Objects.requireNonNull(sourceKind, "sourceKind");
            requireText(adapterId, "adapterId");
            requireText(adapterVersion, "adapterVersion");
            requireText(repositoryId, "repositoryId");
            if (!FULL_SHA.matcher(commitSha).matches()) throw new IllegalArgumentException("commitSha must be a full lowercase SHA");
            requireDigest(contentSha256, "contentSha256");
            Objects.requireNonNull(manifestArtifactId, "manifestArtifactId");
            requireDigest(manifestSha256, "manifestSha256");
            Objects.requireNonNull(workspace, "workspace");
            fileHashes = Map.copyOf(Objects.requireNonNull(fileHashes, "fileHashes"));
            if (fileHashes.isEmpty()) throw new IllegalArgumentException("fileHashes must not be empty");
            fileHashes.forEach((path, hash) -> {
                requireText(path, "fileHashes.path");
                requireDigest(hash, "fileHashes.hash");
            });
            Objects.requireNonNull(materializedAt, "materializedAt");
        }

        /** Compatibility constructor for phase-0 fixtures. */
        public CodeSnapshot(
                String repositoryId, String revision, String contentSha256,
                ArtifactId manifestArtifactId, String readOnlyWorkspaceRef, Instant materializedAt) {
            this("legacy-snapshot", "legacy-source", SourceKind.GITHUB, "legacy-adapter", "1.0.0",
                    repositoryId, revision, contentSha256, manifestArtifactId, contentSha256,
                    new ReadOnlyWorkspaceHandle(readOnlyWorkspaceRef, true), Map.of("legacy", contentSha256), materializedAt);
        }

        public String revision() { return commitSha; }
        public String readOnlyWorkspaceRef() { return workspace.reference(); }
    }

    public record CodeFinding(
            String findingId,
            String ruleId,
            String summary,
            String relativePath,
            String fileSha256,
            int startLine,
            int endLine,
            String repositoryId,
            String commitSha,
            ArtifactId rootArtifactId,
            String analyzerId,
            String analyzerVersion,
            List<ArtifactId> artifactIds) {
        public CodeFinding {
            requireText(findingId, "findingId");
            requireText(ruleId, "ruleId");
            requireText(summary, "summary");
            requireText(relativePath, "relativePath");
            requireDigest(fileSha256, "fileSha256");
            if (startLine < 1 || endLine < startLine) throw new IllegalArgumentException("invalid finding location");
            requireText(repositoryId, "repositoryId");
            requireText(commitSha, "commitSha");
            Objects.requireNonNull(rootArtifactId, "rootArtifactId");
            requireText(analyzerId, "analyzerId");
            requireText(analyzerVersion, "analyzerVersion");
            artifactIds = List.copyOf(artifactIds);
            if (artifactIds.isEmpty()) throw new IllegalArgumentException("artifactIds must not be empty");
        }

        /** Compatibility constructor for phase-0 fixtures. */
        public CodeFinding(
                String findingId, String ruleId, String summary, String relativePath,
                String fileSha256, List<ArtifactId> artifactIds) {
            this(findingId, ruleId, summary, relativePath, fileSha256, 1, 1,
                    "legacy", "legacy", artifactIds.getFirst(), "legacy-analyzer", "1.0.0", artifactIds);
        }
    }

    public enum SourceKind { GITHUB, GITLAB }

    public record ReadOnlyWorkspaceHandle(String reference, boolean readOnly) {
        public ReadOnlyWorkspaceHandle {
            if (reference == null || !reference.startsWith("artifact://code-workspace/")) {
                throw new IllegalArgumentException("workspace must be an opaque controlled artifact reference");
            }
            if (!readOnly) throw new IllegalArgumentException("workspace must be read only");
        }
    }

    public record CodeSourceExecutionContext(
            String sourceId, SourceKind sourceKind, String adapterId, String adapterVersion,
            String connectionRef, Instant deadline, CancellationToken cancellation) {
        public CodeSourceExecutionContext {
            requireText(sourceId, "sourceId");
            Objects.requireNonNull(sourceKind, "sourceKind");
            requireText(adapterId, "adapterId");
            requireText(adapterVersion, "adapterVersion");
            if (connectionRef == null || !connectionRef.startsWith("secret://")) {
                throw new IllegalArgumentException("connectionRef must be a Secret reference");
            }
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(cancellation, "cancellation");
        }

        public CodeSourceExecutionContext(
                String sourceId, SourceKind sourceKind, String adapterId, String adapterVersion,
                String connectionRef, Instant deadline) {
            this(sourceId, sourceKind, adapterId, adapterVersion, connectionRef, deadline, () -> false);
        }
    }

    @FunctionalInterface
    public interface CancellationToken { boolean cancelled(); }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
    }
}
