package io.github.opspilot.adapters.retrieval.infinity;

import java.net.URI;
import java.util.Objects;

/** Immutable identity and shape contract for one Infinity embedding profile. */
public record InfinityEmbeddingConfiguration(
        String providerId,
        URI baseUrl,
        String servedModel,
        String revision,
        int dimension,
        Normalization normalization,
        DistanceMetric distanceMetric,
        int maxBatchItems,
        int providerMaxItems,
        int maxBatchTokens) {
    public InfinityEmbeddingConfiguration {
        requireText(providerId, "providerId");
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!baseUrl.isAbsolute() || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute credential-free API root");
        }
        requireText(servedModel, "servedModel");
        requireText(revision, "revision");
        Objects.requireNonNull(normalization, "normalization");
        Objects.requireNonNull(distanceMetric, "distanceMetric");
        if (dimension < 1 || maxBatchItems < 1 || providerMaxItems < 1 || maxBatchTokens < 1) {
            throw new IllegalArgumentException("dimension and batch limits must be positive");
        }
    }

    public int effectiveMaxBatchItems() {
        return Math.min(maxBatchItems, providerMaxItems);
    }

    public URI embeddingsUri() {
        String root = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(root + "/embeddings");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Normalization { L2_UNIT, NONE }
    public enum DistanceMetric { COSINE, INNER_PRODUCT, L2 }
}
