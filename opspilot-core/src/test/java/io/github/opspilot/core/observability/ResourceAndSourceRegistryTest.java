package io.github.opspilot.core.observability;

import io.github.opspilot.core.application.observability.*;
import io.github.opspilot.core.application.observability.ResourceTopologyService.*;
import io.github.opspilot.core.application.observability.SourceConfigurationService.*;
import io.github.opspilot.core.port.observability.ObservabilitySourceAdapter;
import io.github.opspilot.core.port.observability.ObservationContracts.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class ResourceAndSourceRegistryTest {
    @Test
    void stableTopologyKeepsHistoryAndRejectsRuntimeIdentitiesAndCrossTargetResources() {
        MemoryTopologyRepository repository = new MemoryTopologyRepository();
        ResourceTopologyService service = new ResourceTopologyService(repository);
        Instant first = Instant.parse("2026-07-23T01:00:00Z");
        TopologySnapshot v1 = snapshot("v1", first, null);
        service.register(v1);
        service.register(snapshot("v2", first.plusSeconds(3600), null));

        assertEquals("v1", service.active("sample-commerce", first.plusSeconds(30)).version());
        assertEquals("v2", service.active("sample-commerce", first.plusSeconds(7200)).version());
        assertEquals("v1", repository.findVersion("sample-commerce", "v1").orElseThrow().version());
        service.requireOwned(v1, "sample-commerce", v1.resources().get("service:order-service"));
        assertThrows(TopologyViolation.class, () -> service.requireOwned(
                v1, "other-target", v1.resources().get("service:order-service")));
        assertThrows(TopologyViolation.class, () -> ResourceTopologyService.validateStableIdentity(
                new ResourceRef("10.0.0.4", ResourceType.SERVICE, "sample-commerce",
                        "inventory-service", "local", Map.of())));
        assertThrows(TopologyViolation.class, () -> ResourceTopologyService.validateStableIdentity(
                new ResourceRef("container:" + "a".repeat(64), ResourceType.SERVICE, "sample-commerce",
                        "inventory-service", "local", Map.of())));
    }

    @Test
    void adapterRegistryRejectsDuplicatesProbeFailuresAndMutationAfterSnapshot() {
        SourceAdapterRegistry duplicate = new SourceAdapterRegistry();
        duplicate.register(adapter("metric-adapter", true));
        assertThrows(SourceAdapterRegistry.RegistryException.class,
                () -> duplicate.register(adapter("metric-adapter", true)));

        SourceAdapterRegistry failed = new SourceAdapterRegistry();
        failed.register(adapter("failed-adapter", false));
        assertThrows(SourceAdapterRegistry.RegistryException.class, failed::probeAndFreeze);

        SourceAdapterRegistry frozen = new SourceAdapterRegistry();
        frozen.register(adapter("metric-adapter", true));
        var snapshot = frozen.probeAndFreeze();
        assertEquals(1, snapshot.adapters().size());
        assertThrows(SourceAdapterRegistry.RegistryException.class,
                () -> frozen.register(adapter("trace-adapter", true)));
        assertEquals(snapshot.snapshotId(), frozen.snapshot().snapshotId());
    }

    @Test
    void newLokiAndTempoImplementationsOnlyRequireCompositionRootRegistration() {
        SourceAdapterRegistry registry = new SourceAdapterRegistry();
        registry.register(adapter("loki-log-adapter", true));
        registry.register(adapter("tempo-trace-adapter", true));

        var snapshot = registry.probeAndFreeze();

        assertEquals(Set.of("loki-log-adapter", "tempo-trace-adapter"), snapshot.adapters().stream()
                .map(SourceAdapterRegistry.AdapterCapability::adapterId).collect(java.util.stream.Collectors.toSet()));
        SourceAdapterRegistry restarted = new SourceAdapterRegistry();
        restarted.register(adapter("loki-log-adapter", true));
        restarted.register(adapter("tempo-trace-adapter", true));
        assertNotEquals(snapshot.snapshotId(), restarted.probeAndFreeze().snapshotId());
    }

    @Test
    void sourceConfigurationRequiresExplicitRolesAndSelectorNeverInventsFallbackOrUrl() {
        SourceConfigurationService configurations = new SourceConfigurationService();
        SourceInstance primary = source("prom-primary", SourceRole.PRIMARY, 10, SourceHealth.READY);
        configurations.upsert(primary, "admin");
        configurations.upsert(source("prom-corroborating", SourceRole.CORROBORATING, 20, SourceHealth.READY), "admin");
        assertEquals(2, configurations.audit().size());

        SourceSelector selector = new SourceSelector();
        var selected = selector.select(configurations.snapshot(), request(null, false, null));
        assertEquals(List.of("prom-primary"), selected.sources().stream().map(SourceInstance::sourceId).toList());
        assertEquals(2, selector.select(configurations.snapshot(), request(null, true, null)).sources().size());
        assertThrows(SourceSelector.SourceSelectionException.class,
                () -> selector.select(configurations.snapshot(), request("missing", false, null)));
        assertThrows(SourceSelector.SourceSelectionException.class,
                () -> selector.select(configurations.snapshot(), request(null, false, "https://model.invalid")));

        int before = configurations.snapshot().size();
        assertThrows(SourceConfigurationException.class, () -> configurations.upsert(
                source("conflict", SourceRole.PRIMARY, 30, SourceHealth.READY), "admin"));
        assertEquals(before, configurations.snapshot().size(), "invalid updates must be atomic");
    }

    private static SourceSelector.SelectionRequest request(String required, boolean multi, String modelUrl) {
        return new SourceSelector.SelectionRequest("sample-commerce", UUID.randomUUID().toString(),
                "service:order-service", SignalType.METRIC, "metric/http-v1", required, multi, modelUrl);
    }

    private static SourceInstance source(String id, SourceRole role, int priority, SourceHealth health) {
        return new SourceInstance(id, SourceKind.PROMETHEUS, true, Set.of(SignalType.METRIC),
                "observability-source://sample/" + id, "sample-commerce", Set.of("service:order-service"),
                "metric-adapter", "1.0.0", Set.of("metric/http-v1"), Duration.ofSeconds(3), 2,
                DataClassification.INTERNAL, health, role, priority);
    }

    private static ObservabilitySourceAdapter adapter(String id, boolean ready) {
        return new ObservabilitySourceAdapter() {
            private final SourceDescriptor descriptor = new SourceDescriptor(
                    "source-" + id, SourceKind.PROMETHEUS, id, "1.0.0",
                    "observability-source://test/" + id, "test", Map.of(), Set.of(SignalType.METRIC));
            @Override public SourceDescriptor descriptor() { return descriptor; }
            @Override public ObservationBatch query(ObservationQuery query, SourceExecutionContext context) {
                throw new UnsupportedOperationException();
            }
            @Override public ProbeResult probe() {
                return ready ? ProbeResult.success() : ProbeResult.failed("UNAVAILABLE");
            }
        };
    }

    private static TopologySnapshot snapshot(String version, Instant from, Instant to) {
        Map<String, ResourceRef> resources = Map.of(
                "service:order-service", new ResourceRef("service:order-service", ResourceType.SERVICE,
                        "sample-commerce", "order-service", "local", Map.of()),
                "service:inventory-service", new ResourceRef("service:inventory-service", ResourceType.SERVICE,
                        "sample-commerce", "inventory-service", "local", Map.of()));
        return new TopologySnapshot(UUID.randomUUID(), "sample-commerce", version, from, to, resources,
                List.of(new TopologyRelation("service:order-service", "service:inventory-service", RelationType.CALLS)));
    }

    private static final class MemoryTopologyRepository implements TopologyRepository {
        private final List<TopologySnapshot> snapshots = new ArrayList<>();
        @Override public TopologySnapshot save(TopologySnapshot snapshot) { snapshots.add(snapshot); return snapshot; }
        @Override public Optional<TopologySnapshot> activeAt(String target, Instant at) {
            return snapshots.stream().filter(item -> item.targetSystemId().equals(target)
                            && !item.effectiveFrom().isAfter(at)
                            && (item.effectiveTo() == null || item.effectiveTo().isAfter(at)))
                    .max(Comparator.comparing(TopologySnapshot::effectiveFrom));
        }
        @Override public Optional<TopologySnapshot> findVersion(String target, String version) {
            return snapshots.stream().filter(item -> item.targetSystemId().equals(target)
                    && item.version().equals(version)).findFirst();
        }
    }
}
