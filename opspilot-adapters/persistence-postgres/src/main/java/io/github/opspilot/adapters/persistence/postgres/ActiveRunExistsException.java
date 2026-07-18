package io.github.opspilot.adapters.persistence.postgres;

import java.util.UUID;

/** Domain-facing conflict raised when an incident already has an active run. */
public final class ActiveRunExistsException extends RuntimeException {
    private final UUID existingRunId;

    public ActiveRunExistsException(UUID existingRunId, Throwable cause) {
        super("INCIDENT_ACTIVE_RUN_EXISTS: " + existingRunId, cause);
        this.existingRunId = existingRunId;
    }

    public UUID existingRunId() {
        return existingRunId;
    }
}
