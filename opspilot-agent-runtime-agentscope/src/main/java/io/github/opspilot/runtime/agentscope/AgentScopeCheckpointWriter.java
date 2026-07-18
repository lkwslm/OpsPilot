package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.Objects;

/** Persists AgentScope checkpoints and closes execution if the authoritative store fails. */
public final class AgentScopeCheckpointWriter {

    private final AgentStateStore stateStore;
    private final AgentScopeExecutionGuard executionGuard;

    public AgentScopeCheckpointWriter(
            AgentStateStore stateStore, AgentScopeExecutionGuard executionGuard) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
    }

    public AgentScopeExecutionGuard.StopDecision save(
            String userId, String sessionId, String key, State checkpoint) {
        AgentScopeExecutionGuard.StopDecision current = executionGuard.status();
        if (!current.permitted()) {
            return current;
        }
        try {
            stateStore.save(userId, sessionId, key, checkpoint);
            return executionGuard.status();
        } catch (RuntimeException persistenceFailure) {
            return executionGuard.statePersistenceFailed();
        }
    }
}
