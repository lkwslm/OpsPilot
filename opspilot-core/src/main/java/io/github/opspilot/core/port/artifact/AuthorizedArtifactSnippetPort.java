package io.github.opspilot.core.port.artifact;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import java.util.Objects;

/** Reads only an authorized, bounded artifact location for model context assembly. */
public interface AuthorizedArtifactSnippetPort {
    ArtifactSnippet readAuthorized(String actorId, ArtifactSnippetRequest request);

    record ArtifactSnippetRequest(ArtifactId artifactId, String location, int maxCharacters) {
        public ArtifactSnippetRequest {
            Objects.requireNonNull(artifactId, "artifactId");
            location = requireText(location, "location");
            if (maxCharacters <= 0) {
                throw new IllegalArgumentException("maxCharacters must be positive");
            }
        }
    }

    record ArtifactSnippet(ArtifactId artifactId, String location, String content) {
        public ArtifactSnippet {
            Objects.requireNonNull(artifactId, "artifactId");
            location = requireText(location, "location");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
