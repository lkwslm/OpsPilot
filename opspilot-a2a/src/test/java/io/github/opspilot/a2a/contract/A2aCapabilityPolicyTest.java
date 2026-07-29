package io.github.opspilot.a2a.contract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class A2aCapabilityPolicyTest {

    private final A2aCapabilityPolicy policy = A2aCapabilityPolicy.supervisor();

    @Test
    void identityTargetSkillMediaVersionAndRequiredExtensionsFailClosed() {
        A2aSendRequest valid = new A2aSendRequest("message", "run", "text", false);
        policy.validate(valid, "opspilot-server");

        assertRejected(copy(valid, "attacker", valid.targetAgentId(), valid.skillId(),
                valid.inputMediaType(), valid.protocolVersion(), valid.requiredExtensions()),
                "opspilot-server", "A2A_CALLER_IDENTITY_MISMATCH");
        assertRejected(copy(valid, valid.callerServiceId(), "other", valid.skillId(),
                valid.inputMediaType(), valid.protocolVersion(), valid.requiredExtensions()),
                "opspilot-server", "A2A_TARGET_AGENT_MISMATCH");
        assertRejected(copy(valid, valid.callerServiceId(), valid.targetAgentId(), "other",
                valid.inputMediaType(), valid.protocolVersion(), valid.requiredExtensions()),
                "opspilot-server", "A2A_SKILL_FORBIDDEN");
        assertRejected(copy(valid, valid.callerServiceId(), valid.targetAgentId(), valid.skillId(),
                "text/plain", valid.protocolVersion(), valid.requiredExtensions()),
                "opspilot-server", "A2A_SKILL_MEDIA_TYPE_UNSUPPORTED");
        assertRejected(copy(valid, valid.callerServiceId(), valid.targetAgentId(), valid.skillId(),
                valid.inputMediaType(), "0.3", valid.requiredExtensions()),
                "opspilot-server", "A2A_VERSION_NOT_SUPPORTED");
        var unknown = new ArrayList<>(valid.requiredExtensions());
        unknown.add("urn:unknown:required");
        assertRejected(copy(valid, valid.callerServiceId(), valid.targetAgentId(), valid.skillId(),
                valid.inputMediaType(), valid.protocolVersion(), unknown),
                "opspilot-server", "A2A_REQUIRED_EXTENSION_UNSUPPORTED");
    }

    private void assertRejected(A2aSendRequest request, String identity, String code) {
        assertEquals(code, assertThrows(A2aProtocolException.class,
                () -> policy.validate(request, identity)).code());
    }

    private static A2aSendRequest copy(
            A2aSendRequest source,
            String caller,
            String target,
            String skill,
            String input,
            String version,
            java.util.List<String> required) {
        return new A2aSendRequest(
                source.messageId(), source.contextId(), source.text(), source.deferCompletion(),
                caller, target, skill, input, source.outputMediaType(), version,
                required, source.optionalExtensions());
    }
}
