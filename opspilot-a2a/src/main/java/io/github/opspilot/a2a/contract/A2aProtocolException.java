package io.github.opspilot.a2a.contract;

/** Stable protocol failure that can be mapped without exposing internal details. */
public final class A2aProtocolException extends IllegalStateException {

    private final int status;
    private final String code;

    public A2aProtocolException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
