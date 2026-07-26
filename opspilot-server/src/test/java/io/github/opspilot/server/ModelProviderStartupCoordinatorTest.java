package io.github.opspilot.server;

import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.application.provider.ModelProfileValidation;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.CapabilityStatus;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.ReportEntry;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.CapabilityKind;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProtocolVersion;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRequirement;
import io.github.opspilot.core.port.agent.ChatPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelProviderStartupCoordinatorTest {
    @Test
    void deduplicatesRealModelProbeThenBuildsSnapshotAndFreezesRegistries() {
        AtomicInteger probes = new AtomicInteger();
        ModelProviderStartupCoordinator coordinator = new ModelProviderStartupCoordinator();
        var plan = validPlan(6);

        var first = coordinator.start(plan, request -> {
            probes.incrementAndGet();
            return ModelProviderStartupCoordinator.ProbeResult.temporarilyUnavailable("PROVIDER_CONNECT_TIMEOUT");
        });

        assertEquals(1, probes.get());
        assertTrue(first.health().live());
        assertFalse(first.health().ready());
        assertEquals("PROVIDER_TEMPORARILY_UNAVAILABLE", first.health().reason());
        assertNull(first.registries());
        assertEquals(List.of(
                ModelProviderStartupCoordinator.StartupStage.STATIC_VALIDATE,
                ModelProviderStartupCoordinator.StartupStage.DEDUPLICATE_PROBES,
                ModelProviderStartupCoordinator.StartupStage.PROBE_CAPABILITIES,
                ModelProviderStartupCoordinator.StartupStage.BUILD_CAPABILITY_SNAPSHOT), first.stages());
        assertTrue(first.capabilityReport().entries().stream()
                .allMatch(entry -> entry.status() == CapabilityStatus.UNAVAILABLE));

        var recovered = coordinator.start(plan, request -> {
            probes.incrementAndGet();
            assertEquals(1, request.requiredCapabilities().size());
            return ModelProviderStartupCoordinator.ProbeResult.validated();
        });

        assertEquals(2, probes.get());
        assertTrue(recovered.health().live());
        assertTrue(recovered.health().ready());
        assertTrue(recovered.registries().chat().isFrozen());
        assertEquals(ModelProviderStartupCoordinator.StartupStage.FREEZE_REGISTRIES,
                recovered.stages().getLast());
        assertTrue(recovered.capabilityReport().digest().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void staticConfigurationFailureStopsBeforeAnyProbeAndIsNotLive() {
        AtomicInteger probes = new AtomicInteger();
        ModelProviderStartupCoordinator coordinator = new ModelProviderStartupCoordinator();
        var invalid = new ModelProfileValidation.EnabledProfileCandidate(
                "modelProfiles.diagnosis", "", "", "", "", null, null, null, null,
                null, Set.of(ModelCapability.STRUCTURED_OUTPUT));
        var plan = validPlan(1);
        var invalidPlan = new ModelProviderStartupCoordinator.StartupPlan(
                plan.configVersion(), List.of(new ModelProviderStartupCoordinator.EnabledProfile(
                invalid, plan.profiles().getFirst().target(), plan.profiles().getFirst().reportEntry())),
                plan.chatProviders(), plan.embeddingProviders(), plan.rerankProviders(), plan.requirements());

        assertThrows(ModelProfileValidation.ConfigurationException.class,
                () -> coordinator.start(invalidPlan, request -> {
                    probes.incrementAndGet();
                    return ModelProviderStartupCoordinator.ProbeResult.validated();
                }));

        assertEquals(0, probes.get());
        assertFalse(coordinator.health().live());
        assertFalse(coordinator.health().ready());
        assertEquals("STATIC_CONFIGURATION_INVALID", coordinator.health().reason());
    }

    @Test
    void deterministicProbeIncompatibilityFailsStartupInsteadOfDegrading() {
        ModelProviderStartupCoordinator coordinator = new ModelProviderStartupCoordinator();

        ModelProviderStartupCoordinator.StartupCompatibilityException failure = assertThrows(
                ModelProviderStartupCoordinator.StartupCompatibilityException.class,
                () -> coordinator.start(validPlan(1), request ->
                        ModelProviderStartupCoordinator.ProbeResult.incompatible("MODEL_REVISION_MISMATCH")));

        assertEquals("MODEL_REVISION_MISMATCH", failure.getMessage());
        assertFalse(coordinator.health().live());
        assertEquals("PROVIDER_CAPABILITY_INCOMPATIBLE", coordinator.health().reason());
    }

    private static ModelProviderStartupCoordinator.StartupPlan validPlan(int profileCount) {
        ProviderCapabilityKey key = new ProviderCapabilityKey(
                CapabilityKind.CHAT, "chat.completion", "deepseek", "openai-chat-completions",
                new ProtocolVersion(1, 0), "deepseek-v4-flash", "deepseek-v4-flash");
        ModelProviderStartupCoordinator.ProbeTarget target = new ModelProviderStartupCoordinator.ProbeTarget(
                "deepseek", URI.create("https://api.deepseek.com"),
                "deepseek-v4-flash", "deepseek-v4-flash");
        var configuration = new ModelProfileValidation.EnabledProfileCandidate(
                "modelProfiles.diagnosis", "deepseek", "https://api.deepseek.com",
                "env:DEEPSEEK_API_KEY", "deepseek-v4-flash", 1_000_000, 3, 2, 3_072,
                Set.of(ModelCapability.STRUCTURED_OUTPUT), Set.of(ModelCapability.STRUCTURED_OUTPUT));
        List<ModelProviderStartupCoordinator.EnabledProfile> profiles = java.util.stream.IntStream
                .range(0, profileCount)
                .mapToObj(index -> new ModelProviderStartupCoordinator.EnabledProfile(
                        configuration, target,
                        new ReportEntry(key, "role-" + index, target.baseUrl(), "env:DEEPSEEK_API_KEY",
                                1_000_000, Set.of(ModelCapability.STRUCTURED_OUTPUT), CapabilityStatus.VALIDATED)))
                .toList();
        ChatPort chat = request -> null;
        return new ModelProviderStartupCoordinator.StartupPlan(
                "config-v1", profiles,
                List.of(new OpsPilotCompositionRoot.ChatProviderRegistration("deepseek", chat, Set.of(key))),
                List.of(), List.of(),
                new OpsPilotCompositionRoot.ModelProviderRequirements(
                        Set.of(new ProviderRequirement("deepseek", Set.of(key))), Set.of(), Set.of()));
    }
}
