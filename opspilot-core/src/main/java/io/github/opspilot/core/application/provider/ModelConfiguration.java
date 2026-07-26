package io.github.opspilot.core.application.provider;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic model profile inheritance and non-sensitive persistence mapping. */
public final class ModelConfiguration {
    private ModelConfiguration() {
    }

    public enum AgentRole {
        SUPERVISOR,
        EVIDENCE_COLLECTOR,
        CODE_ANALYSIS,
        KNOWLEDGE,
        DIAGNOSIS,
        REMEDIATION
    }

    public enum ModelPurpose {
        CHAT,
        EMBEDDING,
        RERANK
    }

    public enum ModelCapability {
        TOOL_CALLS,
        STRUCTURED_OUTPUT,
        STREAMING
    }

    public enum ConfigField {
        PROVIDER_ID,
        BASE_URL,
        SECRET_REF,
        MODEL,
        MAX_ATTEMPTS,
        CONCURRENCY_LIMIT,
        CONTEXT_WINDOW_TOKENS,
        CAPABILITIES,
        MAX_OUTPUT_TOKENS_PER_CALL,
        MAX_TASK_TOKENS,
        MAX_CALLS
    }

    public enum SourceLayer {
        PLATFORM_DEFAULT,
        ROLE_OVERRIDE,
        TASK_OVERRIDE
    }

    public record ProviderSettings(
            String stableProviderId,
            URI baseUrl,
            String secretRef,
            String model,
            int maxAttempts,
            int concurrencyLimit,
            int contextWindowTokens,
            Set<ModelCapability> capabilities) {

        public ProviderSettings {
            stableProviderId = requireText(stableProviderId, "stableProviderId");
            Objects.requireNonNull(baseUrl, "baseUrl");
            secretRef = requireText(secretRef, "secretRef");
            model = requireText(model, "model");
            requirePositive(maxAttempts, "maxAttempts");
            requirePositive(concurrencyLimit, "concurrencyLimit");
            requirePositive(contextWindowTokens, "contextWindowTokens");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        }
    }

    /** Null means inherit. An empty capability set intentionally clears inherited capabilities. */
    public record ProviderSettingsPatch(
            String stableProviderId,
            URI baseUrl,
            String secretRef,
            String model,
            Integer maxAttempts,
            Integer concurrencyLimit,
            Integer contextWindowTokens,
            Set<ModelCapability> capabilities) {

        public ProviderSettingsPatch {
            validateOptionalPositive(maxAttempts, "maxAttempts");
            validateOptionalPositive(concurrencyLimit, "concurrencyLimit");
            validateOptionalPositive(contextWindowTokens, "contextWindowTokens");
            if (capabilities != null) {
                capabilities = Set.copyOf(capabilities);
            }
        }

        public static ProviderSettingsPatch empty() {
            return new ProviderSettingsPatch(null, null, null, null, null, null, null, null);
        }
    }

    public record BudgetSettings(int maxOutputTokensPerCall, long maxTaskTokens, int maxCalls) {
        public BudgetSettings {
            requirePositive(maxOutputTokensPerCall, "maxOutputTokensPerCall");
            requirePositive(maxTaskTokens, "maxTaskTokens");
            requirePositive(maxCalls, "maxCalls");
        }
    }

    /** The only fields a single task may override. Null means inherit. */
    public record BudgetSettingsPatch(
            Integer maxOutputTokensPerCall, Long maxTaskTokens, Integer maxCalls) {
        public BudgetSettingsPatch {
            validateOptionalPositive(maxOutputTokensPerCall, "maxOutputTokensPerCall");
            validateOptionalPositive(maxTaskTokens, "maxTaskTokens");
            validateOptionalPositive(maxCalls, "maxCalls");
        }

        public static BudgetSettingsPatch empty() {
            return new BudgetSettingsPatch(null, null, null);
        }
    }

    public record ModelProfile(
            UUID profileId,
            String logicalProfileRef,
            String configVersion,
            ModelPurpose purpose,
            ProviderSettings provider,
            BudgetSettings budget) {
        public ModelProfile {
            Objects.requireNonNull(profileId, "profileId");
            logicalProfileRef = requireText(logicalProfileRef, "logicalProfileRef");
            configVersion = requireText(configVersion, "configVersion");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(budget, "budget");
        }
    }

    public record RoleOverride(
            String overrideVersion,
            ProviderSettingsPatch provider,
            BudgetSettingsPatch budget) {
        public RoleOverride {
            overrideVersion = requireText(overrideVersion, "overrideVersion");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(budget, "budget");
        }

        public static RoleOverride empty(String overrideVersion) {
            return new RoleOverride(overrideVersion, ProviderSettingsPatch.empty(), BudgetSettingsPatch.empty());
        }
    }

    /** This narrow type is the task override whitelist. */
    public record TaskOverride(String overrideId, BudgetSettingsPatch budget) {
        public TaskOverride {
            overrideId = requireText(overrideId, "overrideId");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /** AgentProfile.model carries this logical reference, never a provider URL or credential. */
    public record AgentModelReference(String modelProfileRef) {
        public AgentModelReference {
            modelProfileRef = requireText(modelProfileRef, "modelProfileRef");
        }
    }

    public record ModelConfigurationPlan(
            Map<String, ModelProfile> platformProfiles,
            Map<AgentRole, RoleOverride> roleOverrides) {
        public ModelConfigurationPlan {
            platformProfiles = Map.copyOf(Objects.requireNonNull(platformProfiles, "platformProfiles"));
            roleOverrides = Map.copyOf(Objects.requireNonNull(roleOverrides, "roleOverrides"));
            if (platformProfiles.isEmpty()) {
                throw new IllegalArgumentException("at least one platform model profile is required");
            }
            platformProfiles.forEach((reference, profile) -> {
                if (!reference.equals(profile.logicalProfileRef())) {
                    throw new IllegalArgumentException("platform profile map key must equal logical profile reference");
                }
            });
            if (!roleOverrides.keySet().equals(EnumSet.allOf(AgentRole.class))) {
                throw new IllegalArgumentException("role overrides must contain exactly the six agent roles");
            }
        }
    }

    public record FieldSource(SourceLayer layer, String sourceRef) {
        public FieldSource {
            Objects.requireNonNull(layer, "layer");
            sourceRef = requireText(sourceRef, "sourceRef");
        }
    }

    public record EffectiveModelConfiguration(
            UUID profileId,
            String logicalProfileRef,
            String configVersion,
            ModelPurpose purpose,
            ProviderSettings provider,
            BudgetSettings budget,
            Map<ConfigField, FieldSource> fieldSources) {
        public EffectiveModelConfiguration {
            Objects.requireNonNull(profileId, "profileId");
            logicalProfileRef = requireText(logicalProfileRef, "logicalProfileRef");
            configVersion = requireText(configVersion, "configVersion");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(budget, "budget");
            fieldSources = Map.copyOf(Objects.requireNonNull(fieldSources, "fieldSources"));
            if (!fieldSources.keySet().equals(EnumSet.allOf(ConfigField.class))) {
                throw new IllegalArgumentException("every effective model field must record its source");
            }
        }
    }

    /** Projection of model_profile joined with its immutable configuration version; no Secret value field exists. */
    public record NonSensitiveModelProfileRow(
            UUID profileId,
            String logicalProfileRef,
            String configVersion,
            ModelPurpose purpose,
            String stableProviderId,
            String baseUrl,
            String secretRef,
            String model,
            int maxAttempts,
            int concurrencyLimit,
            int contextWindowTokens,
            Set<ModelCapability> capabilities,
            int maxOutputTokensPerCall,
            long maxTaskTokens,
            int maxCalls) {
    }

    public static ModelProfile mapNonSensitiveProfile(NonSensitiveModelProfileRow row) {
        Objects.requireNonNull(row, "row");
        return new ModelProfile(row.profileId(), row.logicalProfileRef(), row.configVersion(), row.purpose(),
                new ProviderSettings(row.stableProviderId(), URI.create(row.baseUrl()), row.secretRef(), row.model(),
                        row.maxAttempts(), row.concurrencyLimit(), row.contextWindowTokens(), row.capabilities()),
                new BudgetSettings(row.maxOutputTokensPerCall(), row.maxTaskTokens(), row.maxCalls()));
    }

    public static EffectiveModelConfiguration resolve(
            ModelConfigurationPlan plan,
            AgentRole role,
            AgentModelReference reference,
            TaskOverride taskOverride) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(taskOverride, "taskOverride");
        ModelProfile profile = plan.platformProfiles().get(reference.modelProfileRef());
        if (profile == null) {
            throw new IllegalArgumentException("MODEL_PROFILE_NOT_FOUND:" + reference.modelProfileRef());
        }

        EnumMap<ConfigField, FieldSource> sources = new EnumMap<>(ConfigField.class);
        Arrays.stream(ConfigField.values()).forEach(field -> sources.put(
                field, new FieldSource(SourceLayer.PLATFORM_DEFAULT, profile.configVersion())));

        RoleOverride roleOverride = plan.roleOverrides().get(role);
        ProviderSettings provider = applyProviderPatch(
                profile.provider(), roleOverride.provider(), roleOverride.overrideVersion(), sources);
        BudgetSettings budget = applyBudgetPatch(
                profile.budget(), roleOverride.budget(), SourceLayer.ROLE_OVERRIDE,
                roleOverride.overrideVersion(), sources);
        budget = applyBudgetPatch(
                budget, taskOverride.budget(), SourceLayer.TASK_OVERRIDE, taskOverride.overrideId(), sources);

        return new EffectiveModelConfiguration(profile.profileId(), profile.logicalProfileRef(),
                profile.configVersion(), profile.purpose(), provider, budget, sources);
    }

    private static ProviderSettings applyProviderPatch(
            ProviderSettings base,
            ProviderSettingsPatch patch,
            String sourceRef,
            EnumMap<ConfigField, FieldSource> sources) {
        String providerId = base.stableProviderId();
        URI baseUrl = base.baseUrl();
        String secretRef = base.secretRef();
        String model = base.model();
        int maxAttempts = base.maxAttempts();
        int concurrency = base.concurrencyLimit();
        int contextWindow = base.contextWindowTokens();
        Set<ModelCapability> capabilities = base.capabilities();
        if (patch.stableProviderId() != null) {
            providerId = patch.stableProviderId();
            mark(sources, ConfigField.PROVIDER_ID, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.baseUrl() != null) {
            baseUrl = patch.baseUrl();
            mark(sources, ConfigField.BASE_URL, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.secretRef() != null) {
            secretRef = patch.secretRef();
            mark(sources, ConfigField.SECRET_REF, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.model() != null) {
            model = patch.model();
            mark(sources, ConfigField.MODEL, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.maxAttempts() != null) {
            maxAttempts = patch.maxAttempts();
            mark(sources, ConfigField.MAX_ATTEMPTS, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.concurrencyLimit() != null) {
            concurrency = patch.concurrencyLimit();
            mark(sources, ConfigField.CONCURRENCY_LIMIT, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.contextWindowTokens() != null) {
            contextWindow = patch.contextWindowTokens();
            mark(sources, ConfigField.CONTEXT_WINDOW_TOKENS, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        if (patch.capabilities() != null) {
            capabilities = patch.capabilities();
            mark(sources, ConfigField.CAPABILITIES, SourceLayer.ROLE_OVERRIDE, sourceRef);
        }
        return new ProviderSettings(providerId, baseUrl, secretRef, model, maxAttempts,
                concurrency, contextWindow, capabilities);
    }

    private static BudgetSettings applyBudgetPatch(
            BudgetSettings base,
            BudgetSettingsPatch patch,
            SourceLayer layer,
            String sourceRef,
            EnumMap<ConfigField, FieldSource> sources) {
        int maxOutput = base.maxOutputTokensPerCall();
        long maxTask = base.maxTaskTokens();
        int maxCalls = base.maxCalls();
        if (patch.maxOutputTokensPerCall() != null) {
            maxOutput = patch.maxOutputTokensPerCall();
            mark(sources, ConfigField.MAX_OUTPUT_TOKENS_PER_CALL, layer, sourceRef);
        }
        if (patch.maxTaskTokens() != null) {
            maxTask = patch.maxTaskTokens();
            mark(sources, ConfigField.MAX_TASK_TOKENS, layer, sourceRef);
        }
        if (patch.maxCalls() != null) {
            maxCalls = patch.maxCalls();
            mark(sources, ConfigField.MAX_CALLS, layer, sourceRef);
        }
        return new BudgetSettings(maxOutput, maxTask, maxCalls);
    }

    private static void mark(
            EnumMap<ConfigField, FieldSource> sources,
            ConfigField field,
            SourceLayer layer,
            String sourceRef) {
        sources.put(field, new FieldSource(layer, sourceRef));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void validateOptionalPositive(Number value, String field) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive when set");
        }
    }
}
