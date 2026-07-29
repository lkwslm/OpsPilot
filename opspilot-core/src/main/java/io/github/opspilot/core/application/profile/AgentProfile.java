package io.github.opspilot.core.application.profile;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, vendor-neutral policy for one built-in agent role. */
public record AgentProfile(
        String schemaVersion,
        String profileId,
        String profileVersion,
        AgentRole role,
        String skillId,
        String description,
        PromptRef prompt,
        ModelPolicy model,
        ContractRef inputContract,
        ContractRef outputContract,
        ContextPolicy contextPolicy,
        ToolPolicy toolPolicy,
        A2aPolicy a2aPolicy,
        Budget budget,
        SandboxPolicy sandboxPolicy,
        SecurityPolicy securityPolicy,
        TerminationPolicy terminationPolicy) {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    public AgentProfile {
        if (!"1.0.0".equals(schemaVersion)) throw invalid("schemaVersion");
        requireMatch(profileId, PROFILE_ID, "profileId");
        requireMatch(profileVersion, VERSION, "profileVersion");
        Objects.requireNonNull(role, "role");
        requireText(skillId, "skillId");
        if (!role.skillId().equals(skillId)) throw invalid("role/skillId");
        description = description == null ? "" : description;
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(inputContract, "inputContract");
        Objects.requireNonNull(outputContract, "outputContract");
        Objects.requireNonNull(contextPolicy, "contextPolicy");
        Objects.requireNonNull(toolPolicy, "toolPolicy");
        Objects.requireNonNull(a2aPolicy, "a2aPolicy");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(sandboxPolicy, "sandboxPolicy");
        Objects.requireNonNull(securityPolicy, "securityPolicy");
        Objects.requireNonNull(terminationPolicy, "terminationPolicy");
        if (!contextPolicy.factsFromEvidenceOnly()) throw invalid("contextPolicy.factsFromEvidenceOnly");
        if (toolPolicy.dynamicDiscovery()) throw invalid("toolPolicy.dynamicDiscovery");
        if (a2aPolicy.peerToPeerDelegation()) throw invalid("a2aPolicy.peerToPeerDelegation");
        if (securityPolicy.groundTruthAccess() || securityPolicy.secretAccess()
                || securityPolicy.arbitraryCommandExecution() || securityPolicy.codeMutation()) {
            throw invalid("securityPolicy");
        }
        if (model.modelProfileRef().contains("://")) throw invalid("model.modelProfileRef");
    }

    public record ProfileKey(String profileId, String profileVersion) {
        public ProfileKey {
            requireMatch(profileId, PROFILE_ID, "profileId");
            requireMatch(profileVersion, VERSION, "profileVersion");
        }
    }

    public ProfileKey key() {
        return new ProfileKey(profileId, profileVersion);
    }

    public enum AgentRole {
        SUPERVISOR("incident-investigation"),
        EVIDENCE_COLLECTOR("collect-observability-evidence"),
        CODE_ANALYSIS("analyze-code-location"),
        KNOWLEDGE("retrieve-incident-knowledge"),
        DIAGNOSIS("generate-and-verify-hypotheses"),
        REMEDIATION("propose-remediation");

        private final String skillId;

        AgentRole(String skillId) { this.skillId = skillId; }
        public String skillId() { return skillId; }
    }

    public enum ModelCapability { TOOL_CALLS, STRUCTURED_OUTPUT, STREAMING }
    public enum Permission { NONE, READ_ONLY, CONTROLLED_EXECUTION }
    public enum SandboxMode { DISABLED, READ_ONLY, APPROVED_TEST }
    public enum NetworkAccess { DENY, ALLOWLIST }
    public enum DataClassification { PUBLIC, INTERNAL, CONFIDENTIAL }
    public enum CompletionCondition {
        RESULT_SCHEMA_VALID, EVIDENCE_THRESHOLD_MET, NO_PROGRESS,
        BUDGET_EXHAUSTED, INPUT_REQUIRED, CANCELLED
    }

    public record PromptRef(String templateId, String templateVersion, String systemPolicyTemplateId) {
        public PromptRef {
            requireStableId(templateId, "prompt.templateId");
            requireMatch(templateVersion, VERSION, "prompt.templateVersion");
            requireStableId(systemPolicyTemplateId, "prompt.systemPolicyTemplateId");
        }
    }

    public record ModelPolicy(String modelProfileRef, Set<ModelCapability> requiredCapabilities, Generation generation) {
        public ModelPolicy {
            requireStableId(modelProfileRef, "model.modelProfileRef");
            requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
            Objects.requireNonNull(generation, "generation");
        }
    }

    public record Generation(double temperature, int maxOutputTokens, int timeoutMs) {
        public Generation {
            if (temperature < 0 || temperature > 2) throw invalid("model.generation.temperature");
            if (maxOutputTokens < 1) throw invalid("model.generation.maxOutputTokens");
            if (timeoutMs < 1_000 || timeoutMs > 600_000) throw invalid("model.generation.timeoutMs");
        }
    }

    public record ContractRef(String schemaId, String mediaType) {
        public ContractRef {
            requireText(schemaId, "contract.schemaId");
            requireText(mediaType, "contract.mediaType");
            if (!schemaId.startsWith("https://opspilot.local/schemas/")) throw invalid("contract.schemaId");
        }
    }

    public record ContextPolicy(
            Set<String> allowedInputs, boolean factsFromEvidenceOnly,
            int maxEvidenceItems, long maxArtifactBytes) {
        public ContextPolicy {
            allowedInputs = Set.copyOf(Objects.requireNonNull(allowedInputs, "allowedInputs"));
            if (allowedInputs.isEmpty() || maxEvidenceItems < 0 || maxArtifactBytes < 0) {
                throw invalid("contextPolicy");
            }
        }
    }

    public record ToolRef(String toolId, int contractMajor) {
        public ToolRef {
            requireStableId(toolId, "toolPolicy.allowedTools.toolId");
            if (contractMajor < 1) throw invalid("toolPolicy.allowedTools.contractMajor");
        }
    }

    public record ToolPolicy(List<ToolRef> allowedTools, Permission maxPermission, int maxCalls, boolean dynamicDiscovery) {
        public ToolPolicy {
            allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
            Objects.requireNonNull(maxPermission, "maxPermission");
            if (maxCalls < 0) throw invalid("toolPolicy.maxCalls");
            if (maxCalls == 0 && !allowedTools.isEmpty()) throw invalid("toolPolicy.maxCalls");
        }
    }

    public record A2aPolicy(Set<String> allowedSkills, int maxDelegations, boolean peerToPeerDelegation) {
        public A2aPolicy {
            allowedSkills = Set.copyOf(Objects.requireNonNull(allowedSkills, "allowedSkills"));
            if (maxDelegations < 0) throw invalid("a2aPolicy.maxDelegations");
            if (maxDelegations == 0 && !allowedSkills.isEmpty()) throw invalid("a2aPolicy.maxDelegations");
        }
    }

    public record Budget(
            int maxTurns, int maxModelCalls, int maxToolCalls,
            int maxInputTokensPerCall, int maxOutputTokensPerCall,
            int maxTaskTokens, int deadlineSeconds) {
        public Budget {
            if (maxTurns < 1 || maxModelCalls < 1 || maxToolCalls < 0
                    || maxInputTokensPerCall < 1 || maxOutputTokensPerCall < 1
                    || maxTaskTokens < 1 || deadlineSeconds < 1) throw invalid("budget");
        }
    }

    public record ResourceLimits(int maxDurationSeconds, double maxCpu, int maxMemoryMb) {
        public ResourceLimits {
            if (maxDurationSeconds < 1 || maxCpu <= 0 || maxMemoryMb < 64) throw invalid("sandboxPolicy.limits");
        }
    }

    public record SandboxPolicy(
            SandboxMode mode, String runnerRef, boolean requireApproval,
            Set<String> allowedTestSuiteIds, NetworkAccess networkAccess,
            Set<String> writableRoots, ResourceLimits limits) {
        public SandboxPolicy {
            Objects.requireNonNull(mode, "mode");
            allowedTestSuiteIds = Set.copyOf(Objects.requireNonNull(allowedTestSuiteIds, "allowedTestSuiteIds"));
            Objects.requireNonNull(networkAccess, "networkAccess");
            writableRoots = Set.copyOf(Objects.requireNonNull(writableRoots, "writableRoots"));
            if (mode == SandboxMode.DISABLED) {
                if (runnerRef != null || requireApproval || !allowedTestSuiteIds.isEmpty()
                        || networkAccess != NetworkAccess.DENY || !writableRoots.isEmpty() || limits != null) {
                    throw invalid("sandboxPolicy");
                }
            } else {
                requireStableId(runnerRef, "sandboxPolicy.runnerRef");
                Objects.requireNonNull(limits, "sandboxPolicy.limits");
            }
            if (mode == SandboxMode.APPROVED_TEST && (!requireApproval || allowedTestSuiteIds.isEmpty())) {
                throw invalid("sandboxPolicy");
            }
        }
    }

    public record SecurityPolicy(
            String policyRef, Set<String> resourceScopes, DataClassification maxDataClassification,
            boolean groundTruthAccess, boolean secretAccess,
            boolean arbitraryCommandExecution, boolean codeMutation) {
        public SecurityPolicy {
            requireStableId(policyRef, "securityPolicy.policyRef");
            resourceScopes = Set.copyOf(Objects.requireNonNull(resourceScopes, "resourceScopes"));
            Objects.requireNonNull(maxDataClassification, "maxDataClassification");
        }
    }

    public record TerminationPolicy(
            Set<CompletionCondition> completionConditions, int noProgressRounds, String resultMapperRef) {
        public TerminationPolicy {
            completionConditions = Set.copyOf(Objects.requireNonNull(completionConditions, "completionConditions"));
            if (completionConditions.isEmpty() || noProgressRounds < 1 || noProgressRounds > 10) {
                throw invalid("terminationPolicy");
            }
            requireStableId(resultMapperRef, "terminationPolicy.resultMapperRef");
        }
    }

    private static void requireStableId(String value, String field) {
        requireMatch(value, STABLE_ID, field);
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) throw invalid(field);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("AGENT_PROFILE_INVALID:" + field);
    }
}
