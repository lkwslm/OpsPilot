package io.github.opspilot.core.application.correlation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable authority chain propagated across REST, A2A, runtime, tool and provider boundaries. */
public record CorrelationContext(
        String principal,
        UUID requestId,
        UUID traceId,
        UUID incidentId,
        UUID runId,
        UUID stepId,
        String a2aTaskId,
        UUID invocationId) {

    public CorrelationContext {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal is required");
        }
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(traceId, "traceId");
    }

    /** Northbound identifiers are server-owned; caller-supplied authority identifiers are ignored. */
    public static CorrelationContext ingress(String principal) {
        return new CorrelationContext(principal, UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null);
    }

    public CorrelationContext withIncident(UUID authoritativeIncidentId) {
        return new CorrelationContext(principal, requestId, traceId,
                owned("incidentId", incidentId, authoritativeIncidentId), runId, stepId,
                a2aTaskId, invocationId);
    }

    public CorrelationContext withRun(UUID authoritativeRunId) {
        if (incidentId == null) throw new IllegalStateException("incident authority is required before run");
        return new CorrelationContext(principal, requestId, traceId, incidentId,
                owned("runId", runId, authoritativeRunId), stepId, a2aTaskId, invocationId);
    }

    public CorrelationContext withStep(UUID authoritativeStepId) {
        if (runId == null) throw new IllegalStateException("run authority is required before step");
        return new CorrelationContext(principal, requestId, traceId, incidentId, runId,
                owned("stepId", stepId, authoritativeStepId), a2aTaskId, invocationId);
    }

    public CorrelationContext withA2aTask(String authoritativeTaskId) {
        if (stepId == null || authoritativeTaskId == null || authoritativeTaskId.isBlank()) {
            throw new IllegalStateException("step authority and A2A task are required");
        }
        if (a2aTaskId != null && !a2aTaskId.equals(authoritativeTaskId)) {
            throw new AuthorityConflict("a2aTaskId");
        }
        return new CorrelationContext(principal, requestId, traceId, incidentId, runId,
                stepId, authoritativeTaskId, invocationId);
    }

    public CorrelationContext withInvocation(UUID authoritativeInvocationId) {
        if (stepId == null) throw new IllegalStateException("step authority is required before invocation");
        return new CorrelationContext(principal, requestId, traceId, incidentId, runId,
                stepId, a2aTaskId, owned("invocationId", invocationId, authoritativeInvocationId));
    }

    public Map<String, String> propagationHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Request-Id", requestId.toString());
        headers.put("Trace-Id", traceId.toString());
        put(headers, "X-Incident-Id", incidentId);
        put(headers, "X-Run-Id", runId);
        put(headers, "X-Step-Id", stepId);
        if (a2aTaskId != null) headers.put("X-A2A-Task-Id", a2aTaskId);
        put(headers, "X-Invocation-Id", invocationId);
        return Map.copyOf(headers);
    }

    private static UUID owned(String name, UUID existing, UUID authoritative) {
        Objects.requireNonNull(authoritative, name);
        if (existing != null && !existing.equals(authoritative)) throw new AuthorityConflict(name);
        return authoritative;
    }

    private static void put(Map<String, String> headers, String name, UUID value) {
        if (value != null) headers.put(name, value.toString());
    }

    public static final class AuthorityConflict extends RuntimeException {
        public AuthorityConflict(String field) { super("CORRELATION_AUTHORITY_CONFLICT:" + field); }
    }
}
