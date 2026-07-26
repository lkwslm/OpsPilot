package io.github.opspilot.core.governance;

import io.github.opspilot.core.application.governance.ProviderRetryClassifier;
import io.github.opspilot.core.application.governance.ProviderRetryClassifier.FailureKind;
import io.github.opspilot.core.application.governance.ProviderRetryClassifier.Policy;
import io.github.opspilot.core.application.governance.ProviderRetryClassifier.RetryMode;
import io.github.opspilot.core.application.governance.ProviderRetryClassifier.RetryRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRetryClassifierTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final Policy POLICY = new Policy(
            2, Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(100));
    private final ProviderRetryClassifier classifier = new ProviderRetryClassifier(POLICY, () -> 0.5);

    @Test
    void neverRetriesAuthenticationBadRequestOrMissingModel() {
        for (int status : List.of(400, 401, 403, 404)) {
            var decision = classifier.classify(request(FailureKind.HTTP_STATUS, status, 1, null, false, 5));
            assertFalse(decision.retry(), "status " + status);
            assertEquals(RetryMode.NONE, decision.mode());
        }
        assertFalse(classifier.classify(request(FailureKind.MODEL_NOT_FOUND, null, 1, null, false, 5)).retry());
    }

    @Test
    void rateLimitRetryAfterIsClippedAndAuditable() {
        var decision = classifier.classify(request(
                FailureKind.HTTP_STATUS, 429, 1, Duration.ofSeconds(9), false, 5));

        assertTrue(decision.retry());
        assertEquals(Duration.ofSeconds(9), decision.originalRetryAfter());
        assertEquals(Duration.ofSeconds(2), decision.appliedRetryAfter());
        assertEquals(Duration.ofSeconds(2), decision.delay());
    }

    @Test
    void transientFailuresUseExponentialBackoffAndInjectedJitter() {
        var network = classifier.classify(request(FailureKind.NETWORK, null, 1, null, false, 5));

        assertEquals(Duration.ofMillis(250), network.delay());
        assertEquals(RetryMode.REPLAY_SAME_REQUEST, network.mode());
        for (int status : List.of(502, 503, 504)) {
            var unavailable = classifier.classify(
                    request(FailureKind.HTTP_STATUS, status, 1, null, false, 5));
            assertTrue(unavailable.retry(), "status " + status);
            assertEquals(Duration.ofMillis(250), unavailable.delay());
        }
    }

    @Test
    void parentDeadlinePreventsASecondAttempt() {
        var decision = classifier.classify(request(FailureKind.HTTP_STATUS, 503, 1, null, false, 1));

        assertFalse(decision.retry());
        assertEquals("PARENT_DEADLINE_INSUFFICIENT", decision.reason());
    }

    @Test
    void structuredRepairIsAllowedOnlyOnceAndNeverRawReplays() {
        var first = classifier.classify(request(FailureKind.SCHEMA, null, 1, null, false, 5));
        var alreadyUsed = classifier.classify(request(FailureKind.CONTEXT_LIMIT, null, 1, null, true, 5));

        assertTrue(first.retry());
        assertEquals(RetryMode.STRUCTURED_REPAIR, first.mode());
        assertFalse(alreadyUsed.retry());
        assertEquals("STRUCTURED_REPAIR_LIMIT_REACHED", alreadyUsed.reason());
    }

    @Test
    void totalAttemptsAreBoundedAndRetryKeepsIdempotencyAndProviderIdentity() {
        var retry = classifier.classify(request(FailureKind.NETWORK, null, 1, null, false, 5));
        var exhausted = classifier.classify(request(FailureKind.NETWORK, null, 2, null, false, 5));

        assertEquals("deepseek-primary", retry.providerId());
        assertEquals("invocation-42", retry.invocationId());
        assertEquals(2, retry.nextAttempt());
        assertFalse(retry.automaticFailover());
        assertFalse(exhausted.retry());
        assertEquals("ATTEMPT_LIMIT_REACHED", exhausted.reason());
    }

    private static RetryRequest request(
            FailureKind kind,
            Integer status,
            int attempt,
            Duration retryAfter,
            boolean repairUsed,
            long deadlineSeconds) {
        return new RetryRequest("deepseek-primary", "invocation-42", attempt, kind, status, retryAfter,
                repairUsed, NOW, NOW.plusSeconds(deadlineSeconds), Duration.ofSeconds(1));
    }
}
