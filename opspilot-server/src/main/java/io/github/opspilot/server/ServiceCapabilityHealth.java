package io.github.opspilot.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Re-probes required dependencies and produces one auditable UP/DOWN snapshot. */
public final class ServiceCapabilityHealth {
    private final Identity identity;
    private final Set<String> required;
    private final Map<String, CapabilityProbe> probes;
    private final Clock clock;
    private final Duration maximumAge;

    public ServiceCapabilityHealth(
            Identity identity, Set<String> required, Map<String, CapabilityProbe> probes,
            Clock clock, Duration maximumAge) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.required = Set.copyOf(required);
        this.probes = Map.copyOf(probes);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = Objects.requireNonNull(maximumAge, "maximumAge");
        if (required.isEmpty() || !probes.keySet().containsAll(required) || maximumAge.isNegative()
                || maximumAge.isZero()) {
            throw new IllegalArgumentException("required capability probes must be complete and bounded");
        }
    }

    public Snapshot probe(DataState knowledgeDataState) {
        Instant now = clock.instant();
        Map<String, Capability> capabilities = new LinkedHashMap<>();
        probes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ProbeResult result;
            try {
                result = Objects.requireNonNull(entry.getValue().probe(), "probe result");
            } catch (RuntimeException failure) {
                result = ProbeResult.down("PROBE_UNREACHABLE", true);
            }
            capabilities.put(entry.getKey(), new Capability(
                    entry.getKey(), result.state(), required.contains(entry.getKey()), result.reasonCode(),
                    result.retryable(), now, identity.configurationVersion(), identity.directoryDigest(),
                    identity.cardDigest()));
        });
        return new Snapshot(identity, now, Map.copyOf(capabilities), knowledgeDataState);
    }

    public boolean ready(Snapshot snapshot) {
        if (!identity.equals(snapshot.identity())
                || snapshot.probedAt().plus(maximumAge).isBefore(clock.instant())) {
            return false;
        }
        return required.stream().allMatch(id -> {
            Capability capability = snapshot.capabilities().get(id);
            return capability != null && capability.state() == State.UP
                    && identity.configurationVersion().equals(capability.configurationVersion())
                    && identity.directoryDigest().equals(capability.directoryDigest())
                    && identity.cardDigest().equals(capability.cardDigest());
        });
    }

    public void requireTaskAcceptance(Snapshot snapshot) {
        if (!ready(snapshot)) {
            throw new IllegalStateException("SERVICE_NOT_READY");
        }
    }

    public static boolean live(boolean controlLoopAvailable) {
        return controlLoopAvailable;
    }

    public static List<Capability> models(Snapshot snapshot) {
        return snapshot.capabilities().values().stream()
                .filter(value -> value.id().startsWith("model:"))
                .sorted(java.util.Comparator.comparing(Capability::id)).toList();
    }

    public enum State { UP, DOWN }
    public enum DataState { AVAILABLE, KB_EMPTY, NO_MATCH, NOT_APPLICABLE }

    public record Identity(
            String agentId, String configurationVersion, String directoryDigest, String cardDigest) {
        public Identity {
            requireText(agentId, "agentId");
            requireText(configurationVersion, "configurationVersion");
            requireDigest(directoryDigest, "directoryDigest");
            requireDigest(cardDigest, "cardDigest");
        }
    }

    public record Capability(
            String id, State state, boolean required, String reasonCode, boolean retryable,
            Instant probedAt, String configurationVersion, String directoryDigest, String cardDigest) { }

    public record Snapshot(
            Identity identity, Instant probedAt, Map<String, Capability> capabilities,
            DataState knowledgeDataState) {
        public Snapshot { capabilities = Map.copyOf(capabilities); }
    }

    public record ProbeResult(State state, String reasonCode, boolean retryable) {
        public static ProbeResult up() { return new ProbeResult(State.UP, "PROBE_SUCCEEDED", false); }
        public static ProbeResult down(String reasonCode, boolean retryable) {
            return new ProbeResult(State.DOWN, requireText(reasonCode, "reasonCode"), retryable);
        }
    }

    @FunctionalInterface
    public interface CapabilityProbe { ProbeResult probe(); }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
