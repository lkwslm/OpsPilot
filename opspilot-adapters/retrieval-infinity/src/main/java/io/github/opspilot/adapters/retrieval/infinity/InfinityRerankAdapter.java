package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.retrieval.infinity.InfinityRerankHttpClient.InfinityRerankHttpException;
import io.github.opspilot.adapters.retrieval.infinity.InfinityRerankHttpClient.InfinityRerankContractException;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderFailure;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.provider.RerankPort;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Independent Infinity rerank adapter with full-response, fail-closed semantics. */
public final class InfinityRerankAdapter implements RerankPort {
    private final InfinityRerankConfiguration configuration;
    private final InfinityRerankHttpClient http;
    private final InfinityRerankValidator validator;
    private final Clock clock;

    public InfinityRerankAdapter(InfinityRerankConfiguration configuration) {
        this(configuration,
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    InfinityRerankAdapter(
            InfinityRerankConfiguration configuration,
            HttpClient http,
            ObjectMapper json,
            Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.http = new InfinityRerankHttpClient(http, json);
        this.validator = new InfinityRerankValidator();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProviderResult<List<RankedDocument>> rerank(RerankRequest request) {
        try {
            Objects.requireNonNull(request, "request");
            validateIdentity(request.identity());
            validator.validateCandidates(request.candidates());
            Duration remaining = Duration.between(clock.instant(), request.deadline());
            if (remaining.isZero() || remaining.isNegative()) {
                return failure("RERANK_DEADLINE_EXCEEDED", false);
            }
            var payload = new InfinityRerankDtos.Request(configuration.servedModel(),
                    request.query(), request.candidates().stream().map(DocumentCandidate::text).toList(),
                    request.candidates().size(), false);
            InfinityRerankDtos.Response response = http.rerank(
                    configuration.rerankUri(), payload, remaining);
            List<RankedDocument> result = validator.validate(
                    response, request.candidates(), configuration);
            long inputTokens = response.usage() == null ? 0 : response.usage().promptTokens();
            return new ProviderResult<>(result, new ProviderUsage(inputTokens, 0, null), null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure("RERANK_CANCELLED", false);
        } catch (HttpTimeoutException failure) {
            return failure("RERANK_TIMEOUT", true);
        } catch (InfinityRerankContractException failure) {
            return failure("RERANK_RESPONSE_MALFORMED", false);
        } catch (InfinityRerankHttpException failure) {
            boolean retryable = failure.status() == 429
                    || failure.status() >= 500 && failure.status() <= 599;
            return failure("INFINITY_RERANK_HTTP_" + failure.status(), retryable);
        } catch (IOException failure) {
            return failure("INFINITY_RERANK_NETWORK_ERROR", true);
        } catch (NullPointerException | IllegalArgumentException failure) {
            return failure(failure.getMessage(), false);
        }
    }

    private void validateIdentity(ProviderIdentity identity) {
        if (identity == null || !configuration.providerId().equals(identity.providerId())) {
            throw new IllegalArgumentException("RERANK_PROVIDER_MISMATCH");
        }
        if (!configuration.servedModel().equals(identity.modelId())) {
            throw new IllegalArgumentException("RERANK_MODEL_MISMATCH");
        }
        if (!configuration.revision().equals(identity.revision())) {
            throw new IllegalArgumentException("RERANK_REVISION_MISMATCH");
        }
    }

    private static <T> ProviderResult<T> failure(String code, boolean retryable) {
        String stableCode = code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")
                ? "RERANK_CONTRACT_FAILED" : code;
        return new ProviderResult<>(null, null,
                new ProviderFailure(stableCode, retryable, "Infinity rerank invocation failed"));
    }
}
