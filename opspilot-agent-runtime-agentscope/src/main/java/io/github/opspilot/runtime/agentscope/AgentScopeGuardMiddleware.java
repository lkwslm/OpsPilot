package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

/** Enforces pre-invocation limits inside the official AgentScope middleware chain. */
final class AgentScopeGuardMiddleware implements MiddlewareBase {
    private final AgentScopeExecutionGuard guard;
    private final AgentScopeAuditCollector audit;

    AgentScopeGuardMiddleware(AgentScopeExecutionGuard guard, AgentScopeAuditCollector audit) {
        this.guard = guard;
        this.audit = audit;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        int round = guard.usage().rounds() + 1;
        requireAllowed(guard.beforeModelCall(round));
        audit.event("MODEL_STARTED", round, null, null, null, null, false, Map.of());
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        input.toolCalls().forEach(call -> {
            String fingerprint = audit.actionFingerprint("CALL_TOOL", call.getName(), call.getInput());
            requireAllowed(guard.beforeToolCall(fingerprint));
            audit.event("ACTION_SELECTED", guard.usage().rounds(), fingerprint,
                    null, null, null, false, Map.of("toolName", call.getName()));
        });
        return next.apply(input);
    }

    private static void requireAllowed(AgentScopeExecutionGuard.StopDecision decision) {
        if (!decision.permitted()) {
            throw new AgentExecutionStoppedException(decision.reasonCode());
        }
    }
}
