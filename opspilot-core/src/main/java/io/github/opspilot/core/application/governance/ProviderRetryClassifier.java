package io.github.opspilot.core.application.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Shared, same-provider retry policy for chat, embedding, and rerank invocations. */
public final class ProviderRetryClassifier {
    public enum FailureKind {
        HTTP_STATUS,
        NETWORK,
        SCHEMA,
        CONTEXT_LIMIT,
        MODEL_NOT_FOUND,
        CANCELLED,
        OTHER
    }

    public enum RetryMode {
        NONE,
        REPLAY_SAME_REQUEST,
        STRUCTURED_REPAIR
    }

    public record Policy(
            int maxTotalAttempts,
            Duration baseBackoff,
            Duration maxRetryAfter,
            Duration maxJitter) {
        public Policy {
            if (maxTotalAttempts < 1 || maxTotalAttempts > 2) {
                throw new IllegalArgumentException("maxTotalAttempts must be between 1 and 2");
            }
            requireNonNegative(baseBackoff, "baseBackoff");
            requireNonNegative(maxRetryAfter, "maxRetryAfter");
            requireNonNegative(maxJitter, "maxJitter");
        }

        public static Policy defaults() {
            return new Policy(2, Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(100));
        }
    }

    public record RetryRequest(
            String providerId,
            String invocationId,
            int attempt,
            FailureKind failureKind,
            Integer httpStatus,
            Duration retryAfter,
            boolean structuredRepairAlreadyUsed,
            Instant now,
            Instant parentDeadline,
            Duration nextAttemptBudget) {
        public RetryRequest {
            requireText(providerId, "providerId");
            requireText(invocationId, "invocationId");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            Objects.requireNonNull(failureKind, "failureKind");
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
                throw new IllegalArgumentException("httpStatus must be a valid status");
            }
            if (retryAfter != null) {
                requireNonNegative(retryAfter, "retryAfter");
            }
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(parentDeadline, "parentDeadline");
            requireNonNegative(nextAttemptBudget, "nextAttemptBudget");
        }
    }

    public record RetryDecision(
            boolean retry,
            RetryMode mode,
            Duration delay,
            String reason,
            String providerId,
            String invocationId,
            int nextAttempt,
            Duration originalRetryAfter,
            Duration appliedRetryAfter,
            boolean automaticFailover) {
        public RetryDecision {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(delay, "delay");
            requireText(reason, "reason");
            requireText(providerId, "providerId");
            requireText(invocationId, "invocationId");
            if (automaticFailover) {
                throw new IllegalArgumentException("automatic provider failover is forbidden");
            }
        }
    }

    private final Policy policy;
    private final DoubleSupplier jitterUnit;

    public ProviderRetryClassifier(Policy policy, DoubleSupplier jitterUnit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.jitterUnit = Objects.requireNonNull(jitterUnit, "jitterUnit");
    }

    public RetryDecision classify(RetryRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.attempt() >= policy.maxTotalAttempts()) {
            return stop(request, "ATTEMPT_LIMIT_REACHED");
        }

        RetryMode mode;
        Duration delay;
        Duration appliedRetryAfter = null;
        String reason;
        if (neverRetry(request)) {
            return stop(request, "NON_RETRYABLE_FAILURE");
        } else if (requiresStructuredRepair(request)) {
            if (request.structuredRepairAlreadyUsed()) {
                return stop(request, "STRUCTURED_REPAIR_LIMIT_REACHED");
            }
            mode = RetryMode.STRUCTURED_REPAIR;
            delay = Duration.ZERO;
            reason = "STRUCTURED_REPAIR_ALLOWED";
        } else if (request.failureKind() == FailureKind.HTTP_STATUS && request.httpStatus() == 429) {
            Duration original = request.retryAfter() == null ? exponentialBackoff(request.attempt())
                    : request.retryAfter();
            mode = RetryMode.REPLAY_SAME_REQUEST;
            appliedRetryAfter = min(original, policy.maxRetryAfter());
            delay = appliedRetryAfter;
            reason = "RATE_LIMIT_RETRY_AFTER_CLIPPED";
        } else if (isTransient(request)) {
            mode = RetryMode.REPLAY_SAME_REQUEST;
            delay = exponentialBackoff(request.attempt()).plus(jitter());
            reason = "TRANSIENT_FAILURE_BACKOFF";
            return withinDeadline(request, mode, delay, reason, null, null);
        } else {
            return stop(request, "NON_RETRYABLE_FAILURE");
        }

        return withinDeadline(request, mode, delay, reason, request.retryAfter(), appliedRetryAfter);
    }

    private RetryDecision withinDeadline(
            RetryRequest request,
            RetryMode mode,
            Duration delay,
            String reason,
            Duration originalRetryAfter,
            Duration appliedRetryAfter) {
        Duration remaining = request.parentDeadline().isAfter(request.now())
                ? Duration.between(request.now(), request.parentDeadline()) : Duration.ZERO;
        if (delay.plus(request.nextAttemptBudget()).compareTo(remaining) > 0) {
            return stop(request, "PARENT_DEADLINE_INSUFFICIENT", originalRetryAfter, appliedRetryAfter);
        }
        return new RetryDecision(true, mode, delay, reason, request.providerId(), request.invocationId(),
                request.attempt() + 1, originalRetryAfter, appliedRetryAfter, false);
    }

    private RetryDecision stop(RetryRequest request, String reason) {
        return stop(request, reason, request.retryAfter(), null);
    }

    private RetryDecision stop(
            RetryRequest request, String reason, Duration originalRetryAfter, Duration appliedRetryAfter) {
        return new RetryDecision(false, RetryMode.NONE, Duration.ZERO, reason, request.providerId(),
                request.invocationId(), request.attempt(), originalRetryAfter, appliedRetryAfter, false);
    }

    private boolean neverRetry(RetryRequest request) {
        if (request.failureKind() == FailureKind.MODEL_NOT_FOUND
                || request.failureKind() == FailureKind.CANCELLED) {
            return true;
        }
        return request.failureKind() == FailureKind.HTTP_STATUS
                && (request.httpStatus() == 400 || request.httpStatus() == 401 || request.httpStatus() == 403
                || request.httpStatus() == 404);
    }

    private static boolean requiresStructuredRepair(RetryRequest request) {
        return request.failureKind() == FailureKind.SCHEMA
                || request.failureKind() == FailureKind.CONTEXT_LIMIT;
    }

    private static boolean isTransient(RetryRequest request) {
        if (request.failureKind() == FailureKind.NETWORK) {
            return true;
        }
        return request.failureKind() == FailureKind.HTTP_STATUS
                && (request.httpStatus() == 502 || request.httpStatus() == 503 || request.httpStatus() == 504);
    }

    private Duration exponentialBackoff(int attempt) {
        return policy.baseBackoff().multipliedBy(1L << Math.max(0, attempt - 1));
    }

    private Duration jitter() {
        double unit = jitterUnit.getAsDouble();
        if (!Double.isFinite(unit) || unit < 0.0 || unit > 1.0) {
            throw new IllegalStateException("jitterUnit must return a value between 0 and 1");
        }
        return Duration.ofNanos((long) (policy.maxJitter().toNanos() * unit));
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
