package io.github.opspilot.core.application.profile;

import io.github.opspilot.core.application.profile.AgentProfile.AgentRole;
import io.github.opspilot.core.application.profile.AgentProfile.ModelCapability;
import io.github.opspilot.core.application.profile.AgentProfile.ProfileKey;
import io.github.opspilot.core.application.profile.AgentProfile.SandboxMode;
import io.github.opspilot.core.application.profile.AgentProfile.ToolRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates every referenced capability before a profile registry can be frozen. */
public final class ProfileCapabilityClosure {
    private ProfileCapabilityClosure() { }

    public record ServiceIdentity(Set<String> toolIds, Set<String> skillIds, Set<String> resourceScopes) {
        public ServiceIdentity {
            toolIds = Set.copyOf(Objects.requireNonNull(toolIds, "toolIds"));
            skillIds = Set.copyOf(Objects.requireNonNull(skillIds, "skillIds"));
            resourceScopes = Set.copyOf(Objects.requireNonNull(resourceScopes, "resourceScopes"));
        }
    }

    public record CapabilityCatalog(
            Map<String, Set<ModelCapability>> modelCapabilities,
            Map<String, Integer> toolContractMajors,
            Set<String> a2aSkills,
            Set<String> schemaIds,
            Set<String> sandboxRunners,
            Set<String> securityPolicies,
            Map<AgentRole, ServiceIdentity> serviceIdentities) {
        public CapabilityCatalog {
            modelCapabilities = Map.copyOf(Objects.requireNonNull(modelCapabilities, "modelCapabilities"));
            toolContractMajors = Map.copyOf(Objects.requireNonNull(toolContractMajors, "toolContractMajors"));
            a2aSkills = Set.copyOf(Objects.requireNonNull(a2aSkills, "a2aSkills"));
            schemaIds = Set.copyOf(Objects.requireNonNull(schemaIds, "schemaIds"));
            sandboxRunners = Set.copyOf(Objects.requireNonNull(sandboxRunners, "sandboxRunners"));
            securityPolicies = Set.copyOf(Objects.requireNonNull(securityPolicies, "securityPolicies"));
            serviceIdentities = Map.copyOf(Objects.requireNonNull(serviceIdentities, "serviceIdentities"));
        }
    }

    public record ValidatedClosure(ProfileKey profile, String digest, List<String> capabilities) {
        public ValidatedClosure {
            capabilities = List.copyOf(capabilities);
        }
    }

    public static ValidatedClosure validate(AgentProfile profile, CapabilityCatalog catalog) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(catalog, "catalog");
        List<String> capabilities = new ArrayList<>();
        Set<ModelCapability> model = catalog.modelCapabilities().get(profile.model().modelProfileRef());
        require(model != null, "MODEL_PROFILE_MISSING", "model.modelProfileRef");
        require(model.containsAll(profile.model().requiredCapabilities()),
                "MODEL_CAPABILITY_MISSING", "model.requiredCapabilities");
        profile.model().requiredCapabilities().stream().map(Enum::name).sorted()
                .forEach(value -> capabilities.add("model:" + profile.model().modelProfileRef() + ":" + value));

        ServiceIdentity identity = catalog.serviceIdentities().get(profile.role());
        require(identity != null, "SERVICE_IDENTITY_MISSING", "role");
        require(identity.skillIds().contains(profile.skillId()), "SERVICE_SKILL_DENIED", "skillId");
        require(catalog.a2aSkills().contains(profile.skillId()), "A2A_SKILL_MISSING", "skillId");
        capabilities.add("skill:" + profile.skillId());
        for (String skill : profile.a2aPolicy().allowedSkills().stream().sorted().toList()) {
            require(catalog.a2aSkills().contains(skill), "A2A_SKILL_MISSING", "a2aPolicy.allowedSkills");
            require(identity.skillIds().contains(skill), "SERVICE_SKILL_DENIED", "a2aPolicy.allowedSkills");
            capabilities.add("a2a:" + skill);
        }
        for (ToolRef tool : profile.toolPolicy().allowedTools().stream()
                .sorted(Comparator.comparing(ToolRef::toolId)).toList()) {
            Integer major = catalog.toolContractMajors().get(tool.toolId());
            require(major != null, "TOOL_MISSING", "toolPolicy.allowedTools." + tool.toolId());
            require(major == tool.contractMajor(), "TOOL_CONTRACT_MAJOR_INCOMPATIBLE",
                    "toolPolicy.allowedTools." + tool.toolId());
            require(identity.toolIds().contains(tool.toolId()), "SERVICE_TOOL_DENIED",
                    "toolPolicy.allowedTools." + tool.toolId());
            capabilities.add("tool:" + tool.toolId() + ":v" + major);
        }
        for (String schema : List.of(profile.inputContract().schemaId(), profile.outputContract().schemaId())) {
            require(catalog.schemaIds().contains(schema), "SCHEMA_MISSING", "contract.schemaId");
            capabilities.add("schema:" + schema);
        }
        if (profile.sandboxPolicy().mode() != SandboxMode.DISABLED) {
            require(catalog.sandboxRunners().contains(profile.sandboxPolicy().runnerRef()),
                    "SANDBOX_RUNNER_MISSING", "sandboxPolicy.runnerRef");
            capabilities.add("sandbox:" + profile.sandboxPolicy().runnerRef());
        }
        require(catalog.securityPolicies().contains(profile.securityPolicy().policyRef()),
                "SECURITY_POLICY_MISSING", "securityPolicy.policyRef");
        require(identity.resourceScopes().containsAll(profile.securityPolicy().resourceScopes()),
                "SERVICE_SCOPE_DENIED", "securityPolicy.resourceScopes");
        capabilities.add("security:" + profile.securityPolicy().policyRef());
        capabilities.sort(String::compareTo);
        return new ValidatedClosure(profile.key(), digest(profile, capabilities), capabilities);
    }

    private static void require(boolean valid, String code, String fieldPath) {
        if (!valid) throw new CapabilityClosureException(code, fieldPath);
    }

    private static String digest(AgentProfile profile, List<String> capabilities) {
        String payload = profile.profileId() + "|" + profile.profileVersion() + "|"
                + String.join("\n", capabilities);
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static final class CapabilityClosureException extends RuntimeException {
        private final String code;
        private final String fieldPath;

        public CapabilityClosureException(String code, String fieldPath) {
            super(code + ":" + fieldPath);
            this.code = code;
            this.fieldPath = fieldPath;
        }

        public String code() { return code; }
        public String fieldPath() { return fieldPath; }
    }
}
