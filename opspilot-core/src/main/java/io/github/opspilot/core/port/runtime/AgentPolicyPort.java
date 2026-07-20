package io.github.opspilot.core.port.runtime;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.policy.SupervisorPolicy.Decision;
import io.github.opspilot.core.policy.SupervisorPolicy.Evaluation;

/** Events and interrupts consumed by an AgentScope adapter without exposing AgentScope types. */
public interface AgentPolicyPort {
    Decision evaluateStep(Evaluation evaluation);

    void onEvent(RuntimeEvent event);

    boolean interrupted(RunId runId);

    record RuntimeEvent(RunId runId, String eventType, String actionFingerprint, long stateVersion) {
    }
}
