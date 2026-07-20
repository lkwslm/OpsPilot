package io.github.opspilot.core.port.a2a;

import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;

import java.time.Instant;
import java.util.Optional;

/** A2A-runtime-owned protocol task state. It cannot overwrite supervisor or AgentScope state. */
public interface A2aTaskStatePort {
    Optional<TaskState> load(RemoteTaskId taskId);

    void store(TaskState state, long expectedVersion);

    record TaskState(RemoteTaskId taskId, A2aTaskState status, long version, Instant updatedAt) {
    }
}
