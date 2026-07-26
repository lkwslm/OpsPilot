package io.github.opspilot.server;

import io.github.opspilot.core.application.provider.ModelProfileValidation;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.CapabilityStatus;
import io.github.opspilot.core.application.provider.ProviderCapabilityReport.ReportEntry;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import io.github.opspilot.server.OpsPilotCompositionRoot.ChatProviderRegistration;
import io.github.opspilot.server.OpsPilotCompositionRoot.EmbeddingProviderRegistration;
import io.github.opspilot.server.OpsPilotCompositionRoot.ModelProviderRegistries;
import io.github.opspilot.server.OpsPilotCompositionRoot.ModelProviderRequirements;
import io.github.opspilot.server.OpsPilotCompositionRoot.RerankProviderRegistration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed startup sequence for statically configured model providers. */
public final class ModelProviderStartupCoordinator {
    private volatile Health health = new Health(true, false, "MODEL_PROVIDERS_NOT_STARTED");
    private StartupResult completed;

    public synchronized StartupResult start(StartupPlan plan, CapabilityProbe probe) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(probe, "probe");
        if (completed != null && completed.health().ready()) {
            return completed;
        }

        List<StartupStage> stages = new ArrayList<>();
        stages.add(StartupStage.STATIC_VALIDATE);
        try {
            plan.profiles().forEach(profile -> ModelProfileValidation.validate(profile.configuration()));
        } catch (ModelProfileValidation.ConfigurationException failure) {
            health = new Health(false, false, "STATIC_CONFIGURATION_INVALID");
            throw failure;
        }

        stages.add(StartupStage.DEDUPLICATE_PROBES);
        Map<ProbeTarget, Set<ProviderCapabilityKey>> deduplicated = deduplicate(plan.profiles());

        stages.add(StartupStage.PROBE_CAPABILITIES);
        Map<ProbeTarget, ProbeResult> probeResults = new LinkedHashMap<>();
        deduplicated.forEach((target, requiredCapabilities) -> {
            ProbeResult result;
            try {
                result = Objects.requireNonNull(
                        probe.probe(new ProbeRequest(target, requiredCapabilities)), "probe result");
            } catch (RuntimeException temporarilyUnavailable) {
                result = ProbeResult.temporarilyUnavailable("PROVIDER_PROBE_UNAVAILABLE");
            }
            probeResults.put(target, result);
        });

        stages.add(StartupStage.BUILD_CAPABILITY_SNAPSHOT);
        ProviderCapabilityReport report = capabilityReport(plan, probeResults);

        ProbeResult incompatible = probeResults.values().stream()
                .filter(result -> result.outcome() == ProbeOutcome.INCOMPATIBLE)
                .findFirst().orElse(null);
        if (incompatible != null) {
            health = new Health(false, false, "PROVIDER_CAPABILITY_INCOMPATIBLE");
            completed = new StartupResult(health, report, null, stages);
            throw new StartupCompatibilityException(incompatible.reasonCode());
        }
        if (probeResults.values().stream()
                .anyMatch(result -> result.outcome() == ProbeOutcome.TEMPORARILY_UNAVAILABLE)) {
            health = new Health(true, false, "PROVIDER_TEMPORARILY_UNAVAILABLE");
            completed = new StartupResult(health, report, null, stages);
            return completed;
        }

        stages.add(StartupStage.FREEZE_REGISTRIES);
        ModelProviderRegistries registries;
        try {
            registries = OpsPilotCompositionRoot.composeModelProviderRegistries(
                    plan.chatProviders(), plan.embeddingProviders(), plan.rerankProviders(), plan.requirements());
        } catch (RuntimeException configurationFailure) {
            health = new Health(false, false, "PROVIDER_REGISTRY_CONFIGURATION_INVALID");
            throw configurationFailure;
        }
        health = new Health(true, true, "READY");
        completed = new StartupResult(health, report, registries, stages);
        return completed;
    }

    public Health health() {
        return health;
    }

    private static Map<ProbeTarget, Set<ProviderCapabilityKey>> deduplicate(List<EnabledProfile> profiles) {
        Comparator<ProbeTarget> order = Comparator.comparing(ProbeTarget::canonicalValue);
        Map<ProbeTarget, Set<ProviderCapabilityKey>> grouped = new java.util.TreeMap<>(order);
        for (EnabledProfile profile : profiles) {
            grouped.computeIfAbsent(profile.target(), ignored -> new LinkedHashSet<>())
                    .add(profile.reportEntry().key());
        }
        Map<ProbeTarget, Set<ProviderCapabilityKey>> result = new LinkedHashMap<>();
        grouped.forEach((target, required) -> result.put(target, Set.copyOf(required)));
        return result;
    }

    private static ProviderCapabilityReport capabilityReport(
            StartupPlan plan, Map<ProbeTarget, ProbeResult> probeResults) {
        List<ReportEntry> entries = plan.profiles().stream().map(profile -> {
            ReportEntry source = profile.reportEntry();
            ProbeResult probe = probeResults.get(profile.target());
            CapabilityStatus status = probe.outcome() == ProbeOutcome.VALIDATED
                    ? CapabilityStatus.VALIDATED
                    : CapabilityStatus.UNAVAILABLE;
            return new ReportEntry(source.key(), source.logicalProfileRef(), source.baseUrl(), source.secretRef(),
                    source.contextWindowTokens(), source.declaredCapabilities(), status);
        }).toList();
        return new ProviderCapabilityReport(plan.configVersion(), entries);
    }

    public enum StartupStage {
        STATIC_VALIDATE,
        DEDUPLICATE_PROBES,
        PROBE_CAPABILITIES,
        BUILD_CAPABILITY_SNAPSHOT,
        FREEZE_REGISTRIES
    }

    public enum ProbeOutcome {
        VALIDATED,
        TEMPORARILY_UNAVAILABLE,
        INCOMPATIBLE
    }

    public record ProbeTarget(
            String stableProviderId, URI baseUrl, String model, String modelRevision) {
        public ProbeTarget {
            stableProviderId = requireText(stableProviderId, "stableProviderId");
            Objects.requireNonNull(baseUrl, "baseUrl");
            model = requireText(model, "model");
            modelRevision = requireText(modelRevision, "modelRevision");
        }

        String canonicalValue() {
            return stableProviderId + "|" + baseUrl + "|" + model + "|" + modelRevision;
        }
    }

    public record ProbeRequest(ProbeTarget target, Set<ProviderCapabilityKey> requiredCapabilities) {
        public ProbeRequest {
            Objects.requireNonNull(target, "target");
            requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        }
    }

    public record ProbeResult(ProbeOutcome outcome, String reasonCode) {
        public ProbeResult {
            Objects.requireNonNull(outcome, "outcome");
            reasonCode = requireText(reasonCode, "reasonCode");
        }

        public static ProbeResult validated() {
            return new ProbeResult(ProbeOutcome.VALIDATED, "VALIDATED");
        }

        public static ProbeResult temporarilyUnavailable(String reasonCode) {
            return new ProbeResult(ProbeOutcome.TEMPORARILY_UNAVAILABLE, reasonCode);
        }

        public static ProbeResult incompatible(String reasonCode) {
            return new ProbeResult(ProbeOutcome.INCOMPATIBLE, reasonCode);
        }
    }

    public record EnabledProfile(
            ModelProfileValidation.EnabledProfileCandidate configuration,
            ProbeTarget target,
            ReportEntry reportEntry) {
        public EnabledProfile {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reportEntry, "reportEntry");
            if (!target.stableProviderId().equals(reportEntry.key().stableProviderId())
                    || !target.model().equals(reportEntry.key().model())
                    || !target.modelRevision().equals(reportEntry.key().modelRevision())
                    || !target.baseUrl().equals(reportEntry.baseUrl())) {
                throw new IllegalArgumentException("profile probe target must match capability report identity");
            }
        }
    }

    public record StartupPlan(
            String configVersion,
            List<EnabledProfile> profiles,
            List<ChatProviderRegistration> chatProviders,
            List<EmbeddingProviderRegistration> embeddingProviders,
            List<RerankProviderRegistration> rerankProviders,
            ModelProviderRequirements requirements) {
        public StartupPlan {
            configVersion = requireText(configVersion, "configVersion");
            profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
            if (profiles.isEmpty()) {
                throw new IllegalArgumentException("at least one enabled model profile is required");
            }
            chatProviders = List.copyOf(Objects.requireNonNull(chatProviders, "chatProviders"));
            embeddingProviders = List.copyOf(Objects.requireNonNull(embeddingProviders, "embeddingProviders"));
            rerankProviders = List.copyOf(Objects.requireNonNull(rerankProviders, "rerankProviders"));
            Objects.requireNonNull(requirements, "requirements");
        }
    }

    public record Health(boolean live, boolean ready, String reason) {
        public Health {
            reason = requireText(reason, "reason");
        }
    }

    public record StartupResult(
            Health health,
            ProviderCapabilityReport capabilityReport,
            ModelProviderRegistries registries,
            List<StartupStage> stages) {
        public StartupResult {
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(capabilityReport, "capabilityReport");
            stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        }
    }

    @FunctionalInterface
    public interface CapabilityProbe {
        ProbeResult probe(ProbeRequest request);
    }

    public static final class StartupCompatibilityException extends RuntimeException {
        public StartupCompatibilityException(String reasonCode) {
            super(requireText(reasonCode, "reasonCode"));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
