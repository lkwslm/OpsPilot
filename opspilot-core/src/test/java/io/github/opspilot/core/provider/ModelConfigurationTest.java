package io.github.opspilot.core.provider;

import io.github.opspilot.core.application.provider.ModelConfiguration;
import io.github.opspilot.core.application.provider.ModelConfiguration.AgentModelReference;
import io.github.opspilot.core.application.provider.ModelConfiguration.AgentRole;
import io.github.opspilot.core.application.provider.ModelConfiguration.BudgetSettings;
import io.github.opspilot.core.application.provider.ModelConfiguration.BudgetSettingsPatch;
import io.github.opspilot.core.application.provider.ModelConfiguration.ConfigField;
import io.github.opspilot.core.application.provider.ModelConfiguration.EffectiveModelConfiguration;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelConfigurationPlan;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelProfile;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelPurpose;
import io.github.opspilot.core.application.provider.ModelConfiguration.NonSensitiveModelProfileRow;
import io.github.opspilot.core.application.provider.ModelConfiguration.ProviderSettings;
import io.github.opspilot.core.application.provider.ModelConfiguration.ProviderSettingsPatch;
import io.github.opspilot.core.application.provider.ModelConfiguration.RoleOverride;
import io.github.opspilot.core.application.provider.ModelConfiguration.SourceLayer;
import io.github.opspilot.core.application.provider.ModelConfiguration.TaskOverride;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelConfigurationTest {

    @Test
    void resolvesPlatformThenRoleThenWhitelistedTaskFieldsAndRecordsSources() {
        ModelProfile defaults = profile("reasoning-high", "profile-v3", 4, 2048, 30_000, 12);
        Map<AgentRole, RoleOverride> roles = emptyRoleOverrides();
        roles.put(AgentRole.DIAGNOSIS, new RoleOverride("diagnosis-v2",
                new ProviderSettingsPatch(null, null, null, null, null, 2, null,
                        Set.of(ModelCapability.STRUCTURED_OUTPUT)),
                new BudgetSettingsPatch(3072, 40_000L, 10)));
        ModelConfigurationPlan plan = new ModelConfigurationPlan(
                Map.of("reasoning-high", defaults), roles);

        EffectiveModelConfiguration effective = ModelConfiguration.resolve(
                plan,
                AgentRole.DIAGNOSIS,
                new AgentModelReference("reasoning-high"),
                new TaskOverride("task-override-42", new BudgetSettingsPatch(1536, null, 4)));

        assertEquals("openai-compatible", effective.provider().stableProviderId());
        assertEquals(2, effective.provider().concurrencyLimit());
        assertEquals(Set.of(ModelCapability.STRUCTURED_OUTPUT), effective.provider().capabilities());
        assertEquals(1536, effective.budget().maxOutputTokensPerCall());
        assertEquals(40_000, effective.budget().maxTaskTokens());
        assertEquals(4, effective.budget().maxCalls());
        assertEquals(SourceLayer.PLATFORM_DEFAULT,
                effective.fieldSources().get(ConfigField.PROVIDER_ID).layer());
        assertEquals(SourceLayer.ROLE_OVERRIDE,
                effective.fieldSources().get(ConfigField.CONCURRENCY_LIMIT).layer());
        assertEquals("diagnosis-v2",
                effective.fieldSources().get(ConfigField.MAX_TASK_TOKENS).sourceRef());
        assertEquals(SourceLayer.TASK_OVERRIDE,
                effective.fieldSources().get(ConfigField.MAX_OUTPUT_TOKENS_PER_CALL).layer());
        assertEquals("task-override-42",
                effective.fieldSources().get(ConfigField.MAX_CALLS).sourceRef());
        assertEquals(Set.copyOf(Arrays.asList(ConfigField.values())), effective.fieldSources().keySet());
    }

    @Test
    void planRequiresAllSixRoleSlotsAndTaskOverrideCannotCarryProviderFields() {
        ModelProfile defaults = profile("reasoning-high", "profile-v3", 4, 2048, 30_000, 12);
        assertThrows(IllegalArgumentException.class,
                () -> new ModelConfigurationPlan(Map.of("reasoning-high", defaults),
                        Map.of(AgentRole.DIAGNOSIS, RoleOverride.empty("diagnosis-v1"))));

        Set<String> taskFields = Arrays.stream(TaskOverride.class.getRecordComponents())
                .map(component -> component.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("overrideId", "budget"), taskFields);
        assertEquals(Set.of("modelProfileRef"), Arrays.stream(AgentModelReference.class.getRecordComponents())
                .map(component -> component.getName()).collect(Collectors.toSet()));
    }

    @Test
    void nonSensitiveMapperPreservesLogicalProfileVersionAndSecretReferenceOnly() {
        UUID profileId = UUID.randomUUID();
        NonSensitiveModelProfileRow row = new NonSensitiveModelProfileRow(
                profileId, "reasoning-high", "profile-v3", ModelPurpose.CHAT,
                "openai-compatible", "https://api.deepseek.com", "env:DEEPSEEK_API_KEY",
                "locked-chat-model", 2, 4, 128_000,
                Set.of(ModelCapability.STRUCTURED_OUTPUT), 2048, 30_000, 12);

        ModelProfile mapped = ModelConfiguration.mapNonSensitiveProfile(row);

        assertEquals(profileId, mapped.profileId());
        assertEquals("reasoning-high", mapped.logicalProfileRef());
        assertEquals("profile-v3", mapped.configVersion());
        assertEquals("env:DEEPSEEK_API_KEY", mapped.provider().secretRef());
        assertTrue(Arrays.stream(NonSensitiveModelProfileRow.class.getRecordComponents())
                .map(component -> component.getName())
                .noneMatch(name -> name.equalsIgnoreCase("apiKey") || name.equalsIgnoreCase("secretValue")));
    }

    private static Map<AgentRole, RoleOverride> emptyRoleOverrides() {
        Map<AgentRole, RoleOverride> result = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            result.put(role, RoleOverride.empty(role.name().toLowerCase() + "-v1"));
        }
        return result;
    }

    private static ModelProfile profile(
            String logicalRef, String version, int concurrency, int maxOutput, long maxTask, int maxCalls) {
        return new ModelProfile(UUID.randomUUID(), logicalRef, version, ModelPurpose.CHAT,
                new ProviderSettings("openai-compatible", URI.create("https://api.deepseek.com"),
                        "env:DEEPSEEK_API_KEY", "locked-chat-model", 2, concurrency, 128_000,
                        Set.of(ModelCapability.TOOL_CALLS, ModelCapability.STRUCTURED_OUTPUT)),
                new BudgetSettings(maxOutput, maxTask, maxCalls));
    }
}
