package io.github.opspilot.core.application.provider;

import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRequirement;
import io.github.opspilot.core.port.provider.RerankPort;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.opspilot.core.application.provider.ProviderRegistryContracts.CapabilityKind.RERANK;
import static io.github.opspilot.core.application.provider.ProviderRegistryContracts.failure;

/** Explicit, type-safe registry for rerank providers. */
public final class RerankProviderRegistry {
    private static final String REGISTRY_NAME = "rerank";
    private final Map<String, RerankPort> providers = new LinkedHashMap<>();
    private final Map<String, Set<ProviderCapabilityKey>> capabilities = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(
            String stableProviderId, RerankPort provider, Set<ProviderCapabilityKey> capabilityKeys) {
        ensureMutable(stableProviderId);
        Objects.requireNonNull(provider, "provider");
        ProviderRegistryContracts.validateRegistration(REGISTRY_NAME, RERANK, stableProviderId, capabilityKeys);
        if (providers.containsKey(stableProviderId)) {
            throw failure("DUPLICATE_PROVIDER_ID", REGISTRY_NAME, stableProviderId, "provider already registered");
        }
        providers.put(stableProviderId, provider);
        capabilities.put(stableProviderId, Set.copyOf(capabilityKeys));
    }

    public synchronized void replace(
            String stableProviderId, RerankPort provider, Set<ProviderCapabilityKey> capabilityKeys) {
        ensureMutable(stableProviderId);
        Objects.requireNonNull(provider, "provider");
        ProviderRegistryContracts.validateRegistration(REGISTRY_NAME, RERANK, stableProviderId, capabilityKeys);
        requireRegistered(stableProviderId);
        providers.put(stableProviderId, provider);
        capabilities.put(stableProviderId, Set.copyOf(capabilityKeys));
    }

    public synchronized void remove(String stableProviderId) {
        ensureMutable(stableProviderId);
        requireRegistered(stableProviderId);
        providers.remove(stableProviderId);
        capabilities.remove(stableProviderId);
    }

    public synchronized void freeze(Set<ProviderRequirement> requirements) {
        ensureMutable("<registry>");
        ProviderRegistryContracts.validateRequirements(REGISTRY_NAME, RERANK, capabilities, requirements);
        frozen = true;
    }

    public synchronized RerankPort require(ProviderCapabilityKey capabilityKey) {
        ensureFrozen(capabilityKey.stableProviderId());
        RerankPort provider = providers.get(capabilityKey.stableProviderId());
        if (provider == null || capabilities.get(capabilityKey.stableProviderId()).stream()
                .noneMatch(actual -> actual.satisfies(capabilityKey))) {
            throw failure("PROVIDER_CAPABILITY_NOT_AVAILABLE", REGISTRY_NAME,
                    capabilityKey.stableProviderId(), capabilityKey.toString());
        }
        return provider;
    }

    public synchronized Set<ProviderCapabilityKey> capabilityKeys() {
        Set<ProviderCapabilityKey> result = new LinkedHashSet<>();
        capabilities.values().forEach(result::addAll);
        return Set.copyOf(result);
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    private void requireRegistered(String stableProviderId) {
        if (!providers.containsKey(stableProviderId)) {
            throw failure("PROVIDER_NOT_REGISTERED", REGISTRY_NAME, stableProviderId, "provider not registered");
        }
    }

    private void ensureMutable(String stableProviderId) {
        if (frozen) {
            throw failure("PROVIDER_REGISTRY_FROZEN", REGISTRY_NAME, stableProviderId, "registry is frozen");
        }
    }

    private void ensureFrozen(String stableProviderId) {
        if (!frozen) {
            throw failure("PROVIDER_REGISTRY_NOT_FROZEN", REGISTRY_NAME, stableProviderId, "registry is mutable");
        }
    }
}
