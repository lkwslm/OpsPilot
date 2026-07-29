package io.github.opspilot.a2a.contract;

/** Complete official A2A v1.0 task-state view plus invalid wire sentinels. */
public enum A2aTaskState {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    AUTH_REQUIRED,
    COMPLETED,
    CANCELED,
    FAILED,
    REJECTED,
    UNSPECIFIED,
    UNRECOGNIZED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELED || this == FAILED || this == REJECTED;
    }

    public boolean valid() {
        return this != UNSPECIFIED && this != UNRECOGNIZED;
    }
}
