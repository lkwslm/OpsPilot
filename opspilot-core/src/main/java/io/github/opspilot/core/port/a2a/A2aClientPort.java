package io.github.opspilot.core.port.a2a;

import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;

import java.time.Instant;

public interface A2aClientPort {
    RemoteTaskId submit(RunId runId, StepId stepId, String capability, Instant deadline);

    void cancel(RemoteTaskId taskId, Instant deadline);
}
