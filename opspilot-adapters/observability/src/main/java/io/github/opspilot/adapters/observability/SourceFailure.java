package io.github.opspilot.adapters.observability;

import io.github.opspilot.core.domain.failure.ChainFailure;

import java.util.List;
import java.util.UUID;

public final class SourceFailure extends RuntimeException {
    public enum Code {
        SOURCE_TIMEOUT,
        SOURCE_AUTH_FAILED,
        SOURCE_SCHEMA_INVALID,
        SOURCE_CANCELLED,
        SOURCE_NOT_CONFIGURED,
        SOURCE_RATE_LIMITED,
        OBSERVATION_BATCH_INVALID,
        ARTIFACT_HASH_MISMATCH,
        SOURCE_IO_FAILURE
    }

    private final Code code;
    private final String sourceId;

    public SourceFailure(Code code, String sourceId, String message) {
        this(code, sourceId, message, null);
    }

    public SourceFailure(Code code, String sourceId, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
        this.sourceId = sourceId;
    }

    public Code code() {
        return code;
    }

    public String sourceId() {
        return sourceId;
    }

    public ChainFailure chainFailure(UUID correlationId) {
        ChainFailure.Category category = switch (code) {
            case SOURCE_AUTH_FAILED -> ChainFailure.Category.AUTHORIZATION;
            case SOURCE_CANCELLED -> ChainFailure.Category.CANCELLED;
            case SOURCE_SCHEMA_INVALID, OBSERVATION_BATCH_INVALID, ARTIFACT_HASH_MISMATCH ->
                    ChainFailure.Category.VALIDATION;
            default -> ChainFailure.Category.DEPENDENCY;
        };
        boolean retryable = switch (code) {
            case SOURCE_TIMEOUT, SOURCE_RATE_LIMITED, SOURCE_IO_FAILURE -> true;
            default -> false;
        };
        return ChainFailure.fromCause(category, code.name(), retryable, correlationId,
                new ChainFailure.CheckpointRef(UUID.randomUUID(), 0), List.of(), this);
    }
}
