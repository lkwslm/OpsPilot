package io.github.opspilot.core.application.code;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves deployment evidence to the one immutable repository revision an agent may inspect. */
public final class CodeRevisionResolver {
    private static final Pattern FULL_SHA = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private final Map<String, List<DeploymentRevision>> revisionsByResource;
    private final Set<String> configuredRepositories;

    public CodeRevisionResolver(
            Map<String, List<DeploymentRevision>> revisionsByResource,
            Set<String> configuredRepositories) {
        this.revisionsByResource = Map.copyOf(revisionsByResource);
        this.configuredRepositories = Set.copyOf(configuredRepositories);
    }

    public CodeAnalysisScope resolve(String resourceId) {
        if (resourceId == null || resourceId.isBlank() || resourceId.contains("://")
                || resourceId.contains("..") || resourceId.contains("\\")) {
            throw failure("CODE_REVISION_UNRESOLVED", "resourceId");
        }
        List<DeploymentRevision> candidates = revisionsByResource.get(resourceId);
        if (candidates == null || candidates.isEmpty()) throw failure("CODE_REVISION_UNRESOLVED", "resourceId");
        if (candidates.size() != 1) throw failure("CODE_REVISION_UNRESOLVED", "revision");
        DeploymentRevision candidate = candidates.getFirst();
        if (!configuredRepositories.contains(candidate.repositoryId())) {
            throw failure("CODE_SOURCE_NOT_CONFIGURED", "repositoryId");
        }
        if (!IMAGE_DIGEST.matcher(candidate.imageDigest()).matches()
                || !FULL_SHA.matcher(candidate.commitSha()).matches()) {
            throw failure("CODE_REVISION_UNRESOLVED", "revision");
        }
        return new CodeAnalysisScope(resourceId, candidate.imageDigest(),
                candidate.repositoryId(), candidate.commitSha(), Set.of(candidate.repositoryId()));
    }

    public record DeploymentRevision(
            String resourceId, String imageDigest, String repositoryId,
            String commitSha, String resolutionSource) {
        public DeploymentRevision {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(imageDigest, "imageDigest");
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(commitSha, "commitSha");
            if (!Set.of("RELEASE_MANIFEST", "IMAGE_LABEL", "DEPLOYMENT_METADATA").contains(resolutionSource)) {
                throw new IllegalArgumentException("resolutionSource is not authoritative");
            }
        }
    }

    public record CodeAnalysisScope(
            String resourceId, String imageDigest, String repositoryId,
            String commitSha, Set<String> allowedRepositoryIds) {
        public CodeAnalysisScope { allowedRepositoryIds = Set.copyOf(allowedRepositoryIds); }
    }

    private static ResolutionException failure(String code, String fieldPath) {
        return new ResolutionException(code, fieldPath);
    }

    public static final class ResolutionException extends RuntimeException {
        private final String code;
        private final String fieldPath;
        ResolutionException(String code, String fieldPath) {
            super(code + ":" + fieldPath);
            this.code = code;
            this.fieldPath = fieldPath;
        }
        public String code() { return code; }
        public String fieldPath() { return fieldPath; }
    }
}
