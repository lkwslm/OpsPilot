package io.github.opspilot.core.port.runtime;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Agent-runtime-owned session state. This payload is not an IncidentAgentState checkpoint. */
public interface AgentRuntimeStatePort {
    Optional<RuntimeState> load(UUID sessionId);

    void store(RuntimeState state, long expectedVersion);

    record RuntimeState(UUID sessionId, RunId runId, long version, Map<String, String> runtimeValues) {
        public RuntimeState {
            runtimeValues = Map.copyOf(runtimeValues);
        }
    }
}
