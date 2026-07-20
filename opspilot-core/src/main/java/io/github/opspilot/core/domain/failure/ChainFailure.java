package io.github.opspilot.core.domain.failure;

import io.github.opspilot.core.domain.value.DomainValues.Sha256;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable, bounded failure metadata. Raw causes and stack traces never cross this boundary. */
public record ChainFailure(
        UUID failureId,
        Category category,
        String errorCode,
        boolean retryable,
        UUID correlationId,
        CheckpointRef checkpoint,
        List<ControlledLogRef> logRefs,
        String redactedSummary) {
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(secret|password|token|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+", Pattern.CASE_INSENSITIVE);

    public ChainFailure {
        Objects.requireNonNull(failureId, "failureId");
        Objects.requireNonNull(category, "category");
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be stable uppercase notation");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(checkpoint, "checkpoint");
        logRefs = List.copyOf(logRefs);
        redactedSummary = bounded(redact(redactedSummary));
    }

    public static ChainFailure fromCause(
            Category category,
            String errorCode,
            boolean retryable,
            UUID correlationId,
            CheckpointRef checkpoint,
            List<ControlledLogRef> logRefs,
            Throwable cause) {
        String summary = cause == null ? "Unavailable" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new ChainFailure(UUID.randomUUID(), category, errorCode, retryable,
                correlationId, checkpoint, logRefs, summary);
    }

    public enum Category {
        VALIDATION, AUTHORIZATION, DEPENDENCY, PROTOCOL, PERSISTENCE, CONSISTENCY, CANCELLED
    }

    public record CheckpointRef(UUID checkpointId, long stateVersion) {
        public CheckpointRef {
            Objects.requireNonNull(checkpointId, "checkpointId");
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be non-negative");
            }
        }
    }

    public record ControlledLogRef(URI uri, Sha256 sha256) {
        public ControlledLogRef {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(sha256, "sha256");
            if (!"audit".equals(uri.getScheme())) {
                throw new IllegalArgumentException("only controlled audit log references are allowed");
            }
        }
    }

    private static String redact(String value) {
        if (value == null) {
            return "Unavailable";
        }
        return SECRET.matcher(value).replaceAll("$1=[REDACTED]")
                .replaceAll("(?s)\\s+at\\s+.*", "");
    }

    private static String bounded(String value) {
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
