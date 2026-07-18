package io.github.opspilot.adapters.observability;

public final class SourceFailure extends RuntimeException {
    public enum Code {
        SOURCE_TIMEOUT,
        SOURCE_AUTH_FAILED,
        SOURCE_SCHEMA_INVALID,
        SOURCE_CANCELLED,
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
}
