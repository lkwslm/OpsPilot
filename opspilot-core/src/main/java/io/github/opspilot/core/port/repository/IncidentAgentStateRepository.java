package io.github.opspilot.core.port.repository;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.IncidentAgentState;

import java.util.Optional;

/** Supervisor-owned durable checkpoint state. */
public interface IncidentAgentStateRepository {
    Optional<IncidentAgentState> load(RunId runId);

    void save(IncidentAgentState state, long expectedVersion);
}
