package io.github.opspilot.a2a.contract;

import java.util.List;
import java.util.Set;

/** Immutable capability boundary checked before a task is read or created. */
public record A2aCapabilityPolicy(
        String agentId,
        String skillId,
        Set<String> allowedCallerServiceIds,
        Set<String> inputMediaTypes,
        Set<String> outputMediaTypes,
        Set<String> supportedExtensions) {

    public A2aCapabilityPolicy {
        allowedCallerServiceIds = Set.copyOf(allowedCallerServiceIds);
        inputMediaTypes = Set.copyOf(inputMediaTypes);
        outputMediaTypes = Set.copyOf(outputMediaTypes);
        supportedExtensions = Set.copyOf(supportedExtensions);
    }

    public static A2aCapabilityPolicy supervisor() {
        return new A2aCapabilityPolicy(
                "supervisor",
                "incident-investigation",
                Set.of("opspilot-server"),
                Set.of("application/vnd.opspilot.incident-investigation.request+json;v=1"),
                Set.of("application/vnd.opspilot.incident-investigation.result+json;v=1"),
                Set.of(A2aProtocol.CORRELATION_EXTENSION));
    }

    public void validateCaller(String headerServiceId) {
        if (headerServiceId == null || !allowedCallerServiceIds.contains(headerServiceId)) {
            throw new A2aProtocolException(403, "A2A_CALLER_FORBIDDEN");
        }
    }

    public void validate(A2aSendRequest request, String headerServiceId) {
        validateCaller(headerServiceId);
        if (!headerServiceId.equals(request.callerServiceId())) {
            throw new A2aProtocolException(403, "A2A_CALLER_IDENTITY_MISMATCH");
        }
        if (!agentId.equals(request.targetAgentId())) {
            throw new A2aProtocolException(403, "A2A_TARGET_AGENT_MISMATCH");
        }
        if (!skillId.equals(request.skillId())) {
            throw new A2aProtocolException(403, "A2A_SKILL_FORBIDDEN");
        }
        if (!inputMediaTypes.contains(request.inputMediaType())
                || !outputMediaTypes.contains(request.outputMediaType())) {
            throw new A2aProtocolException(415, "A2A_SKILL_MEDIA_TYPE_UNSUPPORTED");
        }
        if (!A2aProtocol.VERSION.equals(request.protocolVersion())) {
            throw new A2aProtocolException(426, "A2A_VERSION_NOT_SUPPORTED");
        }
        if (!supportedExtensions.containsAll(request.requiredExtensions())) {
            throw new A2aProtocolException(400, "A2A_REQUIRED_EXTENSION_UNSUPPORTED");
        }
    }

    public List<String> requiredExtensions() {
        return List.copyOf(supportedExtensions);
    }
}
