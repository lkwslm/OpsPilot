package io.github.opspilot.core.application.state;

/** Stable fail-closed error returned by the checkpoint contract. */
public final class StateContractException extends RuntimeException {
    private final String errorCode;

    public StateContractException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public StateContractException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
