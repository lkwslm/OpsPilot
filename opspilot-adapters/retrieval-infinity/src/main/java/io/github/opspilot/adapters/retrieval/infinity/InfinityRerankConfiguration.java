package io.github.opspilot.adapters.retrieval.infinity;

import java.net.URI;

/** Immutable identity and endpoint lock for one Infinity rerank deployment. */
public record InfinityRerankConfiguration(
        URI rerankUri,
        String providerId,
        String servedModel,
        String revision) {
    public InfinityRerankConfiguration {
        if (rerankUri == null || !rerankUri.isAbsolute()) {
            throw new IllegalArgumentException("rerankUri must be absolute");
        }
        requireText(providerId, "providerId");
        requireText(servedModel, "servedModel");
        requireText(revision, "revision");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
