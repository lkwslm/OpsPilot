package io.github.opspilot.adapters.code.java.source;

/** Stable, redacted code-source failure. */
public final class CodeSourceException extends RuntimeException {
    private final String code;
    private final String fieldPath;

    public CodeSourceException(String code, String fieldPath) {
        super(code + ":" + fieldPath);
        this.code = code;
        this.fieldPath = fieldPath;
    }

    public String code() { return code; }
    public String fieldPath() { return fieldPath; }
}
