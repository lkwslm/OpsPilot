package io.github.opspilot.core.application.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Derives every child deadline by clipping it to the remaining parent deadline. */
public final class DeadlineHierarchy {
    public record Policy(
            Duration maxStep,
            Duration maxRequest,
            Duration maxConnect,
            Duration maxRead) {
        public Policy {
            requirePositive(maxStep, "maxStep");
            requirePositive(maxRequest, "maxRequest");
            requirePositive(maxConnect, "maxConnect");
            requirePositive(maxRead, "maxRead");
        }
    }

    public record Deadlines(
            Instant incident,
            Instant step,
            Instant request,
            Instant connect,
            Instant read) {
        public Deadlines {
            Objects.requireNonNull(incident, "incident");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(connect, "connect");
            Objects.requireNonNull(read, "read");
            if (step.isAfter(incident) || request.isAfter(step)
                    || connect.isAfter(request) || read.isAfter(request)) {
                throw new IllegalArgumentException("child deadline exceeds parent deadline");
            }
        }
    }

    public Deadlines derive(Instant now, Instant incidentDeadline, Policy policy) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(incidentDeadline, "incidentDeadline");
        Objects.requireNonNull(policy, "policy");
        if (!incidentDeadline.isAfter(now)) {
            throw new DeadlineExceededException("INCIDENT_DEADLINE_EXCEEDED");
        }
        Instant step = min(incidentDeadline, now.plus(policy.maxStep()));
        Instant request = min(step, now.plus(policy.maxRequest()));
        Instant connect = min(request, now.plus(policy.maxConnect()));
        Instant read = min(request, connect.plus(policy.maxRead()));
        return new Deadlines(incidentDeadline, step, request, connect, read);
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static void requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    public static final class DeadlineExceededException extends RuntimeException {
        public DeadlineExceededException(String code) {
            super(code);
        }
    }
}
