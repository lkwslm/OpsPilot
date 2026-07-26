package io.github.opspilot.adapters.model.openai;

import io.github.opspilot.adapters.model.openai.OpenAiProviderFailureMapper.FailureContext;
import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.core.domain.failure.ChainFailure.CheckpointRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProviderFailureMapperTest {
    private final OpenAiProviderFailureMapper mapper = new OpenAiProviderFailureMapper();
    private final FailureContext context = new FailureContext(
            "deepseek", 2, UUID.randomUUID(), new CheckpointRef(UUID.randomUUID(), 7));

    @Test
    void mapsHttpStatusesToStableCategoriesWithoutResponseBodies() {
        Map<Integer, Expected> cases = Map.of(
                400, new Expected("PROVIDER_BAD_REQUEST", false),
                401, new Expected("PROVIDER_AUTHENTICATION_FAILED", false),
                403, new Expected("PROVIDER_AUTHENTICATION_FAILED", false),
                404, new Expected("PROVIDER_MODEL_NOT_FOUND", false),
                429, new Expected("PROVIDER_RATE_LIMITED", true),
                502, new Expected("PROVIDER_UNAVAILABLE", true),
                503, new Expected("PROVIDER_UNAVAILABLE", true),
                504, new Expected("PROVIDER_UNAVAILABLE", true));

        cases.forEach((status, expected) -> {
            ChainFailure failure = mapper.map(
                    new OpenAiCompatibleChatModelProvider.HttpStatusException(status, "request-123"), context);
            assertEquals(expected.code(), failure.errorCode());
            assertEquals(expected.retryable(), failure.retryable());
            assertEquals(context.correlationId(), failure.correlationId());
            assertTrue(failure.redactedSummary().contains("attempt=2"));
            assertTrue(failure.redactedSummary().contains("requestId=request-123"));
            assertFalse(failure.redactedSummary().contains("authorization"));
        });
    }

    @Test
    void mapsTransportProtocolStreamAndCancellationFailures() {
        assertMapping(new IOException("source-detail-987"), "PROVIDER_NETWORK_ERROR", true);
        assertMapping(new HttpTimeoutException("source-detail-987"), "PROVIDER_TIMEOUT", true);
        assertMapping(new IllegalArgumentException("source-detail-987"), "PROVIDER_SCHEMA_INVALID", false);
        assertMapping(new OpenAiChatStreamAccumulator.IncompleteStreamException("source-detail-987"),
                "PROVIDER_STREAM_INTERRUPTED", true);
        assertMapping(new CancellationException("source-detail-987"), "PROVIDER_CALL_CANCELLED", false);
    }

    private void assertMapping(Throwable source, String code, boolean retryable) {
        ChainFailure failure = mapper.map(source, context);
        assertEquals(code, failure.errorCode());
        assertEquals(retryable, failure.retryable());
        assertFalse(failure.redactedSummary().contains(source.getMessage()));
    }

    private record Expected(String code, boolean retryable) {
    }
}
