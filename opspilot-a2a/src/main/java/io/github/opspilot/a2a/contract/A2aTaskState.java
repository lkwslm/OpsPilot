package io.github.opspilot.a2a.contract;

/** Minimal Phase 0 task states aligned with the locked A2A task lifecycle. */
public enum A2aTaskState {
    SUBMITTED,
    WORKING,
    COMPLETED,
    CANCELED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELED || this == FAILED;
    }
}
