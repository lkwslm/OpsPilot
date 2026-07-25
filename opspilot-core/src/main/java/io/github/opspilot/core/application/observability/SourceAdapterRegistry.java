package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.port.observability.ObservabilitySourceAdapter;
import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;

import java.time.Instant;
import java.util.*;

/** Explicit adapter registry. Implementation membership freezes after the startup probe. */
public final class SourceAdapterRegistry {
    private final Map<String, ObservabilitySourceAdapter> adapters = new LinkedHashMap<>();
    private CapabilitySnapshot snapshot;

    public synchronized void register(ObservabilitySourceAdapter adapter) {
        if (snapshot != null) throw new RegistryException("ADAPTER_REGISTRY_FROZEN");
        Objects.requireNonNull(adapter, "adapter");
        String adapterId = adapter.descriptor().adapterId();
        if (adapters.putIfAbsent(adapterId, adapter) != null) {
            throw new RegistryException("DUPLICATE_ADAPTER_ID");
        }
    }

    public synchronized CapabilitySnapshot probeAndFreeze() {
        if (snapshot != null) return snapshot;
        List<AdapterCapability> capabilities = new ArrayList<>();
        for (ObservabilitySourceAdapter adapter : adapters.values()) {
            ObservabilitySourceAdapter.ProbeResult probe = adapter.probe();
            capabilities.add(new AdapterCapability(
                    adapter.descriptor().adapterId(), adapter.descriptor().adapterVersion(),
                    adapter.descriptor().capabilities(), probe.ready(), probe.reason()));
        }
        if (capabilities.stream().anyMatch(capability -> !capability.ready())) {
            throw new RegistryException("CAPABILITY_PROBE_FAILED");
        }
        snapshot = new CapabilitySnapshot(UUID.randomUUID(), Instant.now(), capabilities);
        return snapshot;
    }

    public synchronized CapabilitySnapshot snapshot() {
        if (snapshot == null) throw new RegistryException("CAPABILITY_SNAPSHOT_NOT_PUBLISHED");
        return snapshot;
    }

    public synchronized ObservabilitySourceAdapter require(String adapterId, String adapterVersion) {
        if (snapshot == null) throw new RegistryException("CAPABILITY_SNAPSHOT_NOT_PUBLISHED");
        ObservabilitySourceAdapter adapter = adapters.get(adapterId);
        if (adapter == null || !adapter.descriptor().adapterVersion().equals(adapterVersion)) {
            throw new RegistryException("ADAPTER_NOT_READY");
        }
        return adapter;
    }

    public record AdapterCapability(
            String adapterId, String adapterVersion, Set<SignalType> signals, boolean ready, String reason) {
        public AdapterCapability { signals = Set.copyOf(signals); }
    }

    public record CapabilitySnapshot(UUID snapshotId, Instant createdAt, List<AdapterCapability> adapters) {
        public CapabilitySnapshot { adapters = List.copyOf(adapters); }
    }

    public static final class RegistryException extends RuntimeException {
        public RegistryException(String code) { super(code); }
    }
}
