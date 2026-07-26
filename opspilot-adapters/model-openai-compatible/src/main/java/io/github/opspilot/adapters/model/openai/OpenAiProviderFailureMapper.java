package io.github.opspilot.adapters.model.openai;

import io.github.opspilot.adapters.model.openai.OpenAiChatStreamAccumulator.IncompleteStreamException;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleChatModelProvider.HttpStatusException;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleChatModelProvider.CapabilityException;
import io.github.opspilot.adapters.model.openai.OpenAiCompatibleChatModelProvider.DeadlineException;
import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.core.domain.failure.ChainFailure.Category;
import io.github.opspilot.core.domain.failure.ChainFailure.CheckpointRef;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Stable, body-free failure mapping for the OpenAI-compatible boundary. */
public final class OpenAiProviderFailureMapper {
    public ChainFailure map(Throwable failure, FailureContext context) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(context, "context");
        FailureClassification classification = classify(failure);
        String requestId = failure instanceof HttpStatusException status ? status.requestId() : null;
        String summary = "provider=" + context.stableProviderId()
                + ",attempt=" + context.attempt()
                + (requestId == null ? "" : ",requestId=" + boundedIdentifier(requestId))
                + ",reason=" + classification.summary();
        return new ChainFailure(UUID.randomUUID(), classification.category(), classification.code(),
                classification.retryable(), context.correlationId(), context.checkpoint(), List.of(), summary);
    }

    private static FailureClassification classify(Throwable failure) {
        if (failure instanceof CapabilityException) {
            return classification(Category.VALIDATION,
                    "MODEL_CAPABILITY_UNVERIFIED", false, "required capability is not validated");
        }
        if (failure instanceof DeadlineException) {
            return classification(Category.CANCELLED,
                    "PROVIDER_DEADLINE_EXCEEDED", false, "provider deadline exceeded");
        }
        if (failure instanceof HttpStatusException status) {
            return switch (status.statusCode()) {
                case 400 -> classification(Category.VALIDATION, "PROVIDER_BAD_REQUEST", false, "request rejected");
                case 401, 403 -> classification(Category.AUTHORIZATION,
                        "PROVIDER_AUTHENTICATION_FAILED", false, "authentication rejected");
                case 404 -> classification(Category.VALIDATION,
                        "PROVIDER_MODEL_NOT_FOUND", false, "model or resource not found");
                case 429 -> classification(Category.DEPENDENCY,
                        "PROVIDER_RATE_LIMITED", true, "rate limited");
                case 502, 503, 504 -> classification(Category.DEPENDENCY,
                        "PROVIDER_UNAVAILABLE", true, "provider unavailable");
                default -> status.statusCode() >= 500
                        ? classification(Category.DEPENDENCY,
                                "PROVIDER_SERVER_ERROR", true, "provider server error")
                        : classification(Category.PROTOCOL,
                                "PROVIDER_HTTP_ERROR", false, "unexpected HTTP status");
            };
        }
        if (failure instanceof CancellationException || failure instanceof InterruptedException) {
            return classification(Category.CANCELLED, "PROVIDER_CALL_CANCELLED", false, "call cancelled");
        }
        if (failure instanceof HttpTimeoutException) {
            return classification(Category.DEPENDENCY, "PROVIDER_TIMEOUT", true, "call timed out");
        }
        if (failure instanceof IncompleteStreamException) {
            return classification(Category.DEPENDENCY, "PROVIDER_STREAM_INTERRUPTED", true, "stream interrupted");
        }
        if (failure instanceof IOException) {
            return classification(Category.DEPENDENCY, "PROVIDER_NETWORK_ERROR", true, "network unavailable");
        }
        if (failure instanceof IllegalArgumentException) {
            return classification(Category.PROTOCOL, "PROVIDER_SCHEMA_INVALID", false, "response schema invalid");
        }
        return classification(Category.DEPENDENCY, "PROVIDER_CALL_FAILED", false, "provider call failed");
    }

    private static FailureClassification classification(
            Category category, String code, boolean retryable, String summary) {
        return new FailureClassification(category, code, retryable, summary);
    }

    private static String boundedIdentifier(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }

    public record FailureContext(
            String stableProviderId, int attempt, UUID correlationId, CheckpointRef checkpoint) {
        public FailureContext {
            if (stableProviderId == null || stableProviderId.isBlank()) {
                throw new IllegalArgumentException("stableProviderId must not be blank");
            }
            if (attempt <= 0) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }

    private record FailureClassification(Category category, String code, boolean retryable, String summary) {
    }
}
