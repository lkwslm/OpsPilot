package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingBatcher.Batch;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingHttpClient.InfinityHttpException;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderFailure;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Infinity embedding adapter with stable-order batching and strict shape validation. */
public final class InfinityEmbeddingAdapter implements EmbeddingPort {
    private final InfinityEmbeddingConfiguration configuration;
    private final InfinityEmbeddingBatcher batcher;
    private final InfinityEmbeddingHttpClient http;
    private final InfinityVectorValidator vectors;
    private final Clock clock;

    public InfinityEmbeddingAdapter(
            InfinityEmbeddingConfiguration configuration,
            InfinityEmbeddingBatcher.TokenCounter tokenCounter) {
        this(configuration, tokenCounter,
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    InfinityEmbeddingAdapter(
            InfinityEmbeddingConfiguration configuration,
            InfinityEmbeddingBatcher.TokenCounter tokenCounter,
            HttpClient http,
            ObjectMapper json,
            Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.batcher = new InfinityEmbeddingBatcher(configuration.maxBatchItems(),
                configuration.providerMaxItems(), configuration.maxBatchTokens(), tokenCounter);
        this.http = new InfinityEmbeddingHttpClient(http, json);
        this.vectors = new InfinityVectorValidator();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProviderResult<List<float[]>> embed(EmbeddingRequest request) {
        try {
            validateIdentity(request.identity());
            List<Batch> batches = batcher.plan(request.inputs());
            List<float[]> all = new ArrayList<>(request.inputs().size());
            long inputTokens = 0;
            for (Batch batch : batches) {
                Duration remaining = Duration.between(clock.instant(), request.deadline());
                if (remaining.isZero() || remaining.isNegative()) {
                    return failure("EMBEDDING_DEADLINE_EXCEEDED", false);
                }
                List<String> texts = batch.inputs().stream().map(InfinityEmbeddingBatcher.IndexedInput::text).toList();
                var response = http.embed(configuration.embeddingsUri(),
                        new InfinityEmbeddingDtos.EmbeddingRequest(
                                configuration.servedModel(), texts, "float"), remaining);
                List<float[]> validated = vectors.validate(response, texts.size(), configuration);
                all.addAll(validated);
                inputTokens += response.usage() == null ? batch.tokenCount() : response.usage().promptTokens();
            }
            if (all.size() != request.inputs().size()) {
                return failure("EMBEDDING_GLOBAL_COUNT_MISMATCH", false);
            }
            return new ProviderResult<>(List.copyOf(all), new ProviderUsage(inputTokens, 0, null), null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure("EMBEDDING_CANCELLED", false);
        } catch (InfinityHttpException failure) {
            boolean retryable = failure.status() == 429
                    || failure.status() == 502 || failure.status() == 503 || failure.status() == 504;
            return failure("INFINITY_HTTP_" + failure.status(), retryable);
        } catch (IOException failure) {
            return failure("INFINITY_NETWORK_ERROR", true);
        } catch (IllegalArgumentException failure) {
            return failure(failure.getMessage(), false);
        }
    }

    private void validateIdentity(ProviderIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!configuration.providerId().equals(identity.providerId())) {
            throw new IllegalArgumentException("EMBEDDING_PROVIDER_MISMATCH");
        }
        if (!configuration.servedModel().equals(identity.modelId())) {
            throw new IllegalArgumentException("EMBEDDING_MODEL_MISMATCH");
        }
        if (!configuration.revision().equals(identity.revision())) {
            throw new IllegalArgumentException("EMBEDDING_REVISION_MISMATCH");
        }
    }

    private static <T> ProviderResult<T> failure(String code, boolean retryable) {
        String stableCode = code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")
                ? "EMBEDDING_CONTRACT_FAILED" : code;
        return new ProviderResult<>(null, null,
                new ProviderFailure(stableCode, retryable, "Infinity embedding invocation failed"));
    }
}
