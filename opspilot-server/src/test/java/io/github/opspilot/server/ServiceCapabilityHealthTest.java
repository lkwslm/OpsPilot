package io.github.opspilot.server;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServiceCapabilityHealthTest {
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final String DIRECTORY = "a".repeat(64);
    private static final String CARD = "b".repeat(64);

    @Test
    void everySupervisorDependencyDeterministicallyDropsAndRecoversReadiness() {
        Set<String> required = Set.of(
                "database", "agent-state-store", "model:llm", "model:embedding", "model:rerank",
                "agent-runtime", "skill:evidence", "skill:code", "skill:knowledge",
                "skill:diagnosis", "skill:remediation");
        Map<String, AtomicBoolean> availability = new LinkedHashMap<>();
        Map<String, ServiceCapabilityHealth.CapabilityProbe> probes = new LinkedHashMap<>();
        required.stream().sorted().forEach(id -> {
            AtomicBoolean up = new AtomicBoolean(true);
            availability.put(id, up);
            probes.put(id, () -> up.get() ? ServiceCapabilityHealth.ProbeResult.up()
                    : ServiceCapabilityHealth.ProbeResult.down("TEMPORARILY_UNAVAILABLE", true));
        });
        ServiceCapabilityHealth health = service(required, probes);
        assertTrue(health.ready(health.probe(ServiceCapabilityHealth.DataState.AVAILABLE)));

        for (String id : required) {
            availability.get(id).set(false);
            var down = health.probe(ServiceCapabilityHealth.DataState.AVAILABLE);
            assertFalse(health.ready(down), id);
            assertEquals(ServiceCapabilityHealth.State.DOWN, down.capabilities().get(id).state());
            assertTrue(ServiceCapabilityHealth.live(true));
            assertThrows(IllegalStateException.class, () -> health.requireTaskAcceptance(down));
            availability.get(id).set(true);
            assertTrue(health.ready(health.probe(ServiceCapabilityHealth.DataState.AVAILABLE)), id);
        }
    }

    @Test
    void professionalServiceRequiresOwnToolSkillDirectoryCardAndModels() {
        Set<String> required = Set.of(
                "database", "agent-state-store", "model:llm", "tool:logs", "skill:evidence",
                "directory", "card");
        Map<String, ServiceCapabilityHealth.CapabilityProbe> probes = new LinkedHashMap<>();
        required.forEach(id -> probes.put(id, ServiceCapabilityHealth.ProbeResult::up));
        ServiceCapabilityHealth health = service(required, probes);
        assertTrue(health.ready(health.probe(ServiceCapabilityHealth.DataState.NOT_APPLICABLE)));
        assertEquals(1, ServiceCapabilityHealth.models(
                health.probe(ServiceCapabilityHealth.DataState.NOT_APPLICABLE)).size());
    }

    @Test
    void emptyKnowledgeIsDataStateAndNeverSkipsModelProbes() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Set<String> required = Set.of("database", "model:embedding", "model:rerank");
        Map<String, ServiceCapabilityHealth.CapabilityProbe> probes = Map.of(
                "database", ServiceCapabilityHealth.ProbeResult::up,
                "model:embedding", () -> { calls.incrementAndGet(); return ServiceCapabilityHealth.ProbeResult.up(); },
                "model:rerank", () -> { calls.incrementAndGet(); return ServiceCapabilityHealth.ProbeResult.up(); });
        ServiceCapabilityHealth health = service(required, probes);
        var snapshot = health.probe(ServiceCapabilityHealth.DataState.KB_EMPTY);
        assertTrue(health.ready(snapshot));
        assertEquals(ServiceCapabilityHealth.DataState.KB_EMPTY, snapshot.knowledgeDataState());
        assertEquals(2, calls.get());
        assertTrue(ServiceCapabilityHealth.models(snapshot).stream()
                .allMatch(model -> model.state() == ServiceCapabilityHealth.State.UP));
    }

    @Test
    void staleOrVersionMismatchedSnapshotCannotMaintainReady() {
        Set<String> required = Set.of("database");
        ServiceCapabilityHealth health = service(required,
                Map.of("database", ServiceCapabilityHealth.ProbeResult::up));
        var current = health.probe(ServiceCapabilityHealth.DataState.NOT_APPLICABLE);
        assertTrue(health.ready(current));

        var oldIdentity = new ServiceCapabilityHealth.Identity("supervisor", "old", DIRECTORY, CARD);
        var old = new ServiceCapabilityHealth.Snapshot(oldIdentity, current.probedAt(),
                current.capabilities(), current.knowledgeDataState());
        assertFalse(health.ready(old));

        Clock later = Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC);
        ServiceCapabilityHealth expired = new ServiceCapabilityHealth(
                current.identity(), required, Map.of("database", ServiceCapabilityHealth.ProbeResult::up),
                later, Duration.ofSeconds(30));
        assertFalse(expired.ready(current));
    }

    @Test
    void onlyUpAndDownStatesExistAndProviderTimeoutDoesNotKillLiveness() {
        assertEquals(Set.of("UP", "DOWN"), java.util.Arrays.stream(ServiceCapabilityHealth.State.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        ServiceCapabilityHealth health = service(Set.of("model:llm"), Map.of(
                "model:llm", () -> { throw new IllegalStateException("timeout"); }));
        var snapshot = health.probe(ServiceCapabilityHealth.DataState.NOT_APPLICABLE);
        assertFalse(health.ready(snapshot));
        assertEquals(ServiceCapabilityHealth.State.DOWN, snapshot.capabilities().get("model:llm").state());
        assertTrue(snapshot.capabilities().get("model:llm").retryable());
        assertTrue(ServiceCapabilityHealth.live(true));
    }

    private static ServiceCapabilityHealth service(
            Set<String> required, Map<String, ServiceCapabilityHealth.CapabilityProbe> probes) {
        return new ServiceCapabilityHealth(
                new ServiceCapabilityHealth.Identity("supervisor", "v1", DIRECTORY, CARD),
                required, probes, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
    }
}
