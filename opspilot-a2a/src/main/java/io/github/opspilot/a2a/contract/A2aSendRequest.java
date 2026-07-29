package io.github.opspilot.a2a.contract;

import java.util.List;
import java.util.UUID;

/** Project-owned send contract mapped to the official A2A Message object at the boundary. */
public record A2aSendRequest(
        String messageId,
        String contextId,
        String text,
        boolean deferCompletion,
        String callerServiceId,
        String targetAgentId,
        String skillId,
        String inputMediaType,
        String outputMediaType,
        String protocolVersion,
        List<String> requiredExtensions,
        List<String> optionalExtensions,
        String requestId,
        String traceId,
        String runId,
        String stepId,
        String a2aTaskId,
        String invocationId) {

    public A2aSendRequest {
        requiredExtensions = List.copyOf(requiredExtensions);
        optionalExtensions = List.copyOf(optionalExtensions);
        validateUuid("requestId", requestId);
        validateUuid("traceId", traceId);
        validateUuid("runId", runId);
        validateUuid("stepId", stepId);
        validateUuid("invocationId", invocationId);
    }

    public A2aSendRequest(String messageId, String contextId, String text, boolean deferCompletion,
            String callerServiceId, String targetAgentId, String skillId, String inputMediaType,
            String outputMediaType, String protocolVersion, List<String> requiredExtensions,
            List<String> optionalExtensions) {
        this(messageId, contextId, text, deferCompletion, callerServiceId, targetAgentId,
                skillId, inputMediaType, outputMediaType, protocolVersion, requiredExtensions,
                optionalExtensions, null, null, null, null, null, null);
    }

    /** Compatibility constructor retained for the Phase 0 supervisor spike. */
    public A2aSendRequest(String messageId, String contextId, String text, boolean deferCompletion) {
        this(messageId, contextId, text, deferCompletion,
                "opspilot-server", "supervisor", "incident-investigation",
                "application/vnd.opspilot.incident-investigation.request+json;v=1",
                "application/vnd.opspilot.incident-investigation.result+json;v=1",
                A2aProtocol.VERSION,
                List.of(A2aProtocol.CORRELATION_EXTENSION),
                List.of(), null, null, null, null, null, null);
    }

    public A2aSendRequest withCorrelation(UUID requestId, UUID traceId, UUID runId, UUID stepId,
            String a2aTaskId, UUID invocationId) {
        return new A2aSendRequest(messageId, contextId, text, deferCompletion, callerServiceId,
                targetAgentId, skillId, inputMediaType, outputMediaType, protocolVersion,
                requiredExtensions, optionalExtensions, value(requestId), value(traceId),
                value(runId), value(stepId), a2aTaskId, value(invocationId));
    }

    private static String value(UUID value) {
        return value == null ? null : value.toString();
    }

    private static void validateUuid(String name, String value) {
        if (value == null) return;
        try { UUID.fromString(value); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a UUID", exception);
        }
    }
}
