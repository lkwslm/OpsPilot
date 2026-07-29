package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.application.profile.AgentProfile;
import io.github.opspilot.core.application.profile.AgentProfile.AgentRole;
import io.github.opspilot.core.application.profile.AgentProfile.ModelCapability;
import io.github.opspilot.core.application.profile.AgentProfile.Permission;
import io.github.opspilot.core.application.profile.AgentProfileRegistry;
import io.github.opspilot.core.application.profile.EffectivePermissionCalculator;
import io.github.opspilot.core.application.profile.EffectivePermissionCalculator.ActionRequest;
import io.github.opspilot.core.application.profile.EffectivePermissionCalculator.PermissionLayer;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.CapabilityCatalog;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.CapabilityClosureException;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.ServiceIdentity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BundledAgentProfilesTest {

    private final BundledAgentProfiles loader = new BundledAgentProfiles();

    @Test
    void sixVersionedProfilesHaveUniqueRoleSkillPairsAndFreezeWithCompleteClosure() {
        List<AgentProfile> profiles = loader.loadAll();
        assertEquals(6, profiles.size());
        assertEquals(Set.of(AgentRole.values()), profiles.stream().map(AgentProfile::role).collect(Collectors.toSet()));
        assertEquals(6, profiles.stream().map(AgentProfile::key).distinct().count());
        profiles.forEach(profile -> {
            assertEquals(profile.role().skillId(), profile.skillId());
            assertTrue(profile.contextPolicy().factsFromEvidenceOnly());
            assertFalse(profile.toolPolicy().dynamicDiscovery());
            assertFalse(profile.a2aPolicy().peerToPeerDelegation());
        });

        AgentProfileRegistry registry = loader.registerAll();
        Map<AgentProfile.ProfileKey, ?> closures = registry.validateAndFreeze(catalog(profiles));
        assertEquals(6, closures.size());
        assertTrue(registry.isFrozen());
        profiles.forEach(profile -> {
            assertEquals(profile, registry.require(profile.profileId(), profile.profileVersion()));
            assertEquals(profile, registry.latest(profile.profileId()));
            assertTrue(registry.closure(profile.key()).digest().startsWith("sha256:"));
        });
        assertEquals("PROFILE_REGISTRY_FROZEN", assertThrows(
                AgentProfileRegistry.ProfileRegistryException.class,
                () -> registry.register(profiles.getFirst())).code());
    }

    @Test
    void duplicateAndIncompleteBuiltInSetAreRejected() {
        List<AgentProfile> profiles = loader.loadAll();
        AgentProfileRegistry duplicate = new AgentProfileRegistry();
        duplicate.register(profiles.getFirst());
        assertEquals("DUPLICATE_PROFILE_VERSION", assertThrows(
                AgentProfileRegistry.ProfileRegistryException.class,
                () -> duplicate.register(profiles.getFirst())).code());

        AgentProfileRegistry incomplete = new AgentProfileRegistry();
        profiles.subList(0, 5).forEach(incomplete::register);
        assertEquals("BUILT_IN_ROLE_SET_INCOMPLETE", assertThrows(
                AgentProfileRegistry.ProfileRegistryException.class,
                () -> incomplete.validateAndFreeze(catalog(profiles))).code());
    }

    @Test
    void missingAndIncompatibleClosureCapabilitiesFailClosed() {
        List<AgentProfile> profiles = loader.loadAll();
        AgentProfile evidence = byRole(profiles, AgentRole.EVIDENCE_COLLECTOR);
        CapabilityCatalog complete = catalog(profiles);

        Map<String, Set<ModelCapability>> noModel = new HashMap<>(complete.modelCapabilities());
        noModel.remove(evidence.model().modelProfileRef());
        assertClosureCode(evidence, copy(complete, noModel, null, null, null, null, null, null), "MODEL_PROFILE_MISSING");

        Map<String, Integer> wrongTool = new HashMap<>(complete.toolContractMajors());
        wrongTool.put(evidence.toolPolicy().allowedTools().getFirst().toolId(), 2);
        assertClosureCode(evidence, copy(complete, null, wrongTool, null, null, null, null, null),
                "TOOL_CONTRACT_MAJOR_INCOMPATIBLE");

        Set<String> noSkill = new HashSet<>(complete.a2aSkills());
        noSkill.remove(evidence.skillId());
        assertClosureCode(evidence, copy(complete, null, null, noSkill, null, null, null, null), "A2A_SKILL_MISSING");

        Set<String> noSchema = new HashSet<>(complete.schemaIds());
        noSchema.remove(evidence.outputContract().schemaId());
        assertClosureCode(evidence, copy(complete, null, null, null, noSchema, null, null, null), "SCHEMA_MISSING");

        AgentProfile code = byRole(profiles, AgentRole.CODE_ANALYSIS);
        assertClosureCode(code, copy(complete, null, null, null, null, Set.of(), null, null),
                "SANDBOX_RUNNER_MISSING");

        Set<String> noPolicy = new HashSet<>(complete.securityPolicies());
        noPolicy.remove(evidence.securityPolicy().policyRef());
        assertClosureCode(evidence, copy(complete, null, null, null, null, null, noPolicy, null),
                "SECURITY_POLICY_MISSING");
    }

    @Test
    void strictFiveLayerIntersectionRestrictsEveryRoleAndApprovalCannotAllowHighRisk() {
        List<AgentProfile> profiles = loader.loadAll();
        for (AgentProfile profile : profiles) {
            String forbiddenTool = "UnregisteredTool";
            List<PermissionLayer> layers = layers(profile, Set.of(forbiddenTool), Set.of("foreign-skill"));
            assertFalse(EffectivePermissionCalculator.decide(
                    new ActionRequest(forbiddenTool, null, "run:${runId}", Permission.READ_ONLY, false, false), layers).allowed());
            assertFalse(EffectivePermissionCalculator.decide(
                    new ActionRequest(null, "foreign-skill", "run:${runId}", Permission.READ_ONLY, false, false), layers).allowed());
        }

        AgentProfile remediation = byRole(profiles, AgentRole.REMEDIATION);
        List<PermissionLayer> approved = layers(remediation, Set.of("SandboxTestTool"), Set.of());
        var decision = EffectivePermissionCalculator.decide(
                new ActionRequest("SandboxTestTool", null, "run:${runId}",
                        Permission.CONTROLLED_EXECUTION, true, false), approved);
        assertFalse(decision.allowed());
        assertEquals("PLATFORM_BASELINE_DENIED", decision.code());
        assertEquals(5, decision.layers().size());
    }

    @Test
    void sensitiveOrUnknownConfigurationIsRejectedWithoutEchoingValue() throws IOException {
        String original;
        try (var input = getClass().getClassLoader().getResourceAsStream("agent-profiles/diagnosis.json")) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String marker = "do-not-echo-provider-key";
        String sensitive = original.replace("\"profileVersion\": \"1.0.0\",",
                "\"profileVersion\": \"1.0.0\", \"apiKey\": \"" + marker + "\",");
        BundledAgentProfiles.ProfileLoadException failure = assertThrows(
                BundledAgentProfiles.ProfileLoadException.class,
                () -> loader.loadDocument(stream(sensitive), "malicious.json"));
        assertEquals("PROFILE_SENSITIVE_FIELD", failure.code());
        assertFalse(failure.getMessage().contains(marker));

        String unknown = original.replace("\"profileVersion\": \"1.0.0\",",
                "\"profileVersion\": \"1.0.0\", \"providerConfiguration\": {},");
        BundledAgentProfiles.ProfileLoadException unknownFailure = assertThrows(
                BundledAgentProfiles.ProfileLoadException.class,
                () -> loader.loadDocument(stream(unknown), "unknown.json"));
        assertEquals("PROFILE_FIELD_UNKNOWN", unknownFailure.code());
    }

    @Test
    void capturedProfileSnapshotDoesNotChangeWhenNewProfileObjectExists() {
        List<AgentProfile> profiles = loader.loadAll();
        AgentProfile v1 = byRole(profiles, AgentRole.DIAGNOSIS);
        var closure = io.github.opspilot.core.application.profile.ProfileCapabilityClosure.validate(v1, catalog(profiles));
        var snapshot = io.github.opspilot.core.application.profile.AgentProfileSnapshot.capture(
                v1, closure, Set.of("run:${runId}"));
        AgentProfile v2 = new AgentProfile(v1.schemaVersion(), v1.profileId(), "2.0.0", v1.role(), v1.skillId(),
                v1.description(), new AgentProfile.PromptRef(v1.prompt().templateId(), "2.0.0",
                v1.prompt().systemPolicyTemplateId()), v1.model(), v1.inputContract(), v1.outputContract(),
                v1.contextPolicy(), v1.toolPolicy(), v1.a2aPolicy(), v1.budget(), v1.sandboxPolicy(),
                v1.securityPolicy(), v1.terminationPolicy());
        assertEquals("1.0.0", snapshot.profile().profileVersion());
        assertEquals("1.0.0", snapshot.promptVersion());
        assertNotEquals(v2.profileVersion(), snapshot.profile().profileVersion());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AgentProfile byRole(List<AgentProfile> profiles, AgentRole role) {
        return profiles.stream().filter(profile -> profile.role() == role).findFirst().orElseThrow();
    }

    private static void assertClosureCode(AgentProfile profile, CapabilityCatalog catalog, String code) {
        assertEquals(code, assertThrows(CapabilityClosureException.class,
                () -> io.github.opspilot.core.application.profile.ProfileCapabilityClosure.validate(profile, catalog)).code());
    }

    private static CapabilityCatalog catalog(List<AgentProfile> profiles) {
        Map<String, Set<ModelCapability>> models = Map.of(
                "reasoning-high", Set.of(ModelCapability.values()),
                "reasoning-standard", Set.of(ModelCapability.values()));
        Map<String, Integer> tools = profiles.stream().flatMap(profile -> profile.toolPolicy().allowedTools().stream())
                .collect(Collectors.toMap(AgentProfile.ToolRef::toolId, AgentProfile.ToolRef::contractMajor, (left, right) -> left));
        Set<String> skills = profiles.stream().map(AgentProfile::skillId).collect(Collectors.toSet());
        Set<String> schemas = profiles.stream().flatMap(profile ->
                java.util.stream.Stream.of(profile.inputContract().schemaId(), profile.outputContract().schemaId()))
                .collect(Collectors.toSet());
        Set<String> runners = profiles.stream().map(profile -> profile.sandboxPolicy().runnerRef())
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<String> policies = profiles.stream().map(profile -> profile.securityPolicy().policyRef()).collect(Collectors.toSet());
        Map<AgentRole, ServiceIdentity> identities = new EnumMap<>(AgentRole.class);
        profiles.forEach(profile -> identities.put(profile.role(), new ServiceIdentity(
                profile.toolPolicy().allowedTools().stream().map(AgentProfile.ToolRef::toolId).collect(Collectors.toSet()),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(profile.skillId()),
                        profile.a2aPolicy().allowedSkills().stream()).collect(Collectors.toSet()),
                profile.securityPolicy().resourceScopes())));
        return new CapabilityCatalog(models, tools, skills, schemas, runners, policies, identities);
    }

    private static CapabilityCatalog copy(
            CapabilityCatalog source,
            Map<String, Set<ModelCapability>> models,
            Map<String, Integer> tools,
            Set<String> skills,
            Set<String> schemas,
            Set<String> runners,
            Set<String> policies,
            Map<AgentRole, ServiceIdentity> identities) {
        return new CapabilityCatalog(
                models == null ? source.modelCapabilities() : models,
                tools == null ? source.toolContractMajors() : tools,
                skills == null ? source.a2aSkills() : skills,
                schemas == null ? source.schemaIds() : schemas,
                runners == null ? source.sandboxRunners() : runners,
                policies == null ? source.securityPolicies() : policies,
                identities == null ? source.serviceIdentities() : identities);
    }

    private static List<PermissionLayer> layers(
            AgentProfile profile, Set<String> requestedTools, Set<String> requestedSkills) {
        Set<String> profileTools = profile.toolPolicy().allowedTools().stream()
                .map(AgentProfile.ToolRef::toolId).collect(Collectors.toSet());
        Set<String> profileSkills = profile.a2aPolicy().allowedSkills();
        Set<String> scopes = profile.securityPolicy().resourceScopes();
        PermissionLayer platform = new PermissionLayer("platform", requestedTools, requestedSkills, scopes,
                Permission.CONTROLLED_EXECUTION, true);
        PermissionLayer identity = new PermissionLayer("identity", requestedTools, requestedSkills, scopes,
                Permission.CONTROLLED_EXECUTION, true);
        PermissionLayer profileLayer = new PermissionLayer("profile", profileTools, profileSkills, scopes,
                profile.toolPolicy().maxPermission(), true);
        PermissionLayer task = new PermissionLayer("task", requestedTools, requestedSkills, scopes,
                Permission.CONTROLLED_EXECUTION, true);
        PermissionLayer approval = new PermissionLayer("approval", requestedTools, requestedSkills, scopes,
                Permission.CONTROLLED_EXECUTION, true);
        return List.of(platform, identity, profileLayer, task, approval);
    }
}
