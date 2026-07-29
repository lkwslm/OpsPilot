package io.github.opspilot.core.application.correlation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Consistent HTTP, A2A, MDC and telemetry projection with recovery authority checks. */
public final class CorrelationBoundaryMapper {
    private CorrelationBoundaryMapper() { }

    public static Map<String, String> httpHeaders(CorrelationContext context) {
        return context.propagationHeaders();
    }

    public static Map<String, Object> a2aMetadata(CorrelationContext context) {
        return Map.copyOf(new LinkedHashMap<>(context.propagationHeaders()));
    }

    public static Map<String, String> mdc(CorrelationContext context) {
        return namespaced(context, "");
    }

    public static Map<String, String> telemetryAttributes(CorrelationContext context) {
        return namespaced(context, "opspilot.");
    }

    /** Recovery data is authoritative; a queued payload may repeat but never replace its identifiers. */
    public static CorrelationContext restore(CorrelationContext authoritative,
            Map<String, ?> propagated) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(propagated, "propagated");
        verify(propagated, "X-Request-Id", authoritative.requestId());
        verify(propagated, "Trace-Id", authoritative.traceId());
        verify(propagated, "X-Incident-Id", authoritative.incidentId());
        verify(propagated, "X-Run-Id", authoritative.runId());
        verify(propagated, "X-Step-Id", authoritative.stepId());
        verify(propagated, "X-A2A-Task-Id", authoritative.a2aTaskId());
        verify(propagated, "X-Invocation-Id", authoritative.invocationId());
        return authoritative;
    }

    private static Map<String, String> namespaced(CorrelationContext context, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        context.propagationHeaders().forEach((key, value) -> result.put(prefix + name(key), value));
        return Map.copyOf(result);
    }

    private static String name(String header) {
        return header.toLowerCase().replace("x-", "").replace('-', '.');
    }

    private static void verify(Map<String, ?> values, String name, Object authoritative) {
        Object supplied = values.get(name);
        if (supplied == null) return;
        String expected = authoritative instanceof UUID id ? id.toString()
                : authoritative == null ? null : authoritative.toString();
        if (!Objects.equals(expected, supplied.toString())) {
            throw new CorrelationContext.AuthorityConflict(name);
        }
    }
}
