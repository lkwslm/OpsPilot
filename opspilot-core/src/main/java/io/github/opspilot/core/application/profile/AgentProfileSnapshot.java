package io.github.opspilot.core.application.profile;

import io.github.opspilot.core.application.profile.AgentProfile.ProfileKey;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.ValidatedClosure;

import java.util.Objects;
import java.util.Set;

/** Secret-free immutable profile interpretation captured for a run or task. */
public record AgentProfileSnapshot(
        String schemaVersion,
        ProfileKey profile,
        String promptTemplateId,
        String promptVersion,
        String systemPolicyTemplateId,
        String modelProfileRef,
        String inputSchemaId,
        String outputSchemaId,
        String capabilityClosureDigest,
        Set<String> taskConstraints) {

    public AgentProfileSnapshot {
        if (!"1.0.0".equals(schemaVersion)) throw new IllegalArgumentException("schemaVersion must be 1.0.0");
        Objects.requireNonNull(profile, "profile");
        requireText(promptTemplateId, "promptTemplateId");
        requireText(promptVersion, "promptVersion");
        requireText(systemPolicyTemplateId, "systemPolicyTemplateId");
        requireText(modelProfileRef, "modelProfileRef");
        requireText(inputSchemaId, "inputSchemaId");
        requireText(outputSchemaId, "outputSchemaId");
        requireText(capabilityClosureDigest, "capabilityClosureDigest");
        taskConstraints = Set.copyOf(Objects.requireNonNull(taskConstraints, "taskConstraints"));
    }

    public static AgentProfileSnapshot capture(
            AgentProfile profile, ValidatedClosure closure, Set<String> taskConstraints) {
        if (!profile.key().equals(closure.profile())) throw new IllegalArgumentException("PROFILE_CLOSURE_MISMATCH");
        return new AgentProfileSnapshot(
                "1.0.0", profile.key(), profile.prompt().templateId(), profile.prompt().templateVersion(),
                profile.prompt().systemPolicyTemplateId(), profile.model().modelProfileRef(),
                profile.inputContract().schemaId(), profile.outputContract().schemaId(),
                closure.digest(), taskConstraints);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
