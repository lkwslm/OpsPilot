package io.github.opspilot.core.port.knowledge;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;

import java.util.List;

public final class KnowledgeContracts {
    private KnowledgeContracts() {
    }

    public record KnowledgeResult(
            String resultId,
            String knowledgeBaseId,
            String revision,
            String summary,
            List<ArtifactId> artifactIds) {
        public KnowledgeResult { artifactIds = List.copyOf(artifactIds); }
    }
}
