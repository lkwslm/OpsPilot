package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Versioned logical topology whose identities are independent of runtime container details. */
public final class ResourceTopologyService {
    private static final Pattern HEX_ID = Pattern.compile("(?i)^(sha256:)?[a-f0-9]{40,64}$");
    private static final Pattern JAVA_CLASS = Pattern.compile("^(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z_$][\\w$]*$");
    private final TopologyRepository repository;

    public ResourceTopologyService(TopologyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TopologySnapshot register(TopologySnapshot snapshot) {
        validate(snapshot);
        return repository.save(snapshot);
    }

    public TopologySnapshot active(String targetSystemId, Instant at) {
        return repository.activeAt(targetSystemId, at)
                .orElseThrow(() -> new TopologyViolation("TOPOLOGY_NOT_CONFIGURED"));
    }

    public void requireOwned(TopologySnapshot snapshot, String targetSystemId, ResourceRef resource) {
        if (!snapshot.targetSystemId().equals(targetSystemId)
                || !snapshot.resources().containsKey(resource.resourceId())
                || !Objects.equals(snapshot.resources().get(resource.resourceId()).systemId(), resource.systemId())) {
            throw new TopologyViolation("RESOURCE_SCOPE_DENIED");
        }
    }

    private static void validate(TopologySnapshot snapshot) {
        required(snapshot.targetSystemId(), "targetSystemId");
        required(snapshot.version(), "version");
        Objects.requireNonNull(snapshot.effectiveFrom(), "effectiveFrom");
        if (snapshot.effectiveTo() != null && !snapshot.effectiveTo().isAfter(snapshot.effectiveFrom())) {
            throw new TopologyViolation("TOPOLOGY_EFFECTIVE_WINDOW_INVALID");
        }
        if (snapshot.resources().isEmpty()) throw new TopologyViolation("TOPOLOGY_EMPTY");
        snapshot.resources().forEach((id, resource) -> {
            if (!id.equals(resource.resourceId()) || !snapshot.targetSystemId().equals(resource.systemId())) {
                throw new TopologyViolation("RESOURCE_IDENTITY_INVALID");
            }
            validateStableIdentity(resource);
        });
        for (TopologyRelation relation : snapshot.relations()) {
            if (relation.sourceResourceId().equals(relation.targetResourceId())
                    || !snapshot.resources().containsKey(relation.sourceResourceId())
                    || !snapshot.resources().containsKey(relation.targetResourceId())) {
                throw new TopologyViolation("TOPOLOGY_RELATION_INVALID");
            }
        }
    }

    public static void validateStableIdentity(ResourceRef resource) {
        required(resource.resourceId(), "resourceId");
        required(resource.systemId(), "systemId");
        required(resource.environment(), "environment");
        String identity = resource.resourceId();
        if (looksLikeIp(identity) || HEX_ID.matcher(identity).matches() || JAVA_CLASS.matcher(identity).matches()
                || identity.startsWith("pod:") || identity.startsWith("container:")) {
            throw new TopologyViolation("UNSTABLE_RESOURCE_IDENTITY");
        }
        if (resource.resourceType() == ResourceType.SERVICE && !identity.startsWith("service:")) {
            throw new TopologyViolation("SERVICE_IDENTITY_INVALID");
        }
    }

    private static boolean looksLikeIp(String value) {
        if (!value.matches("[0-9a-fA-F:.]+")) return false;
        try { InetAddress.getByName(value); return true; } catch (Exception ignored) { return false; }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new TopologyViolation(field + "_MISSING");
    }

    public enum RelationType { CALLS, DEPENDS_ON, RUNS_ON, READS_FROM, WRITES_TO, PUBLISHES_TO, CONSUMES_FROM }

    public record TopologyRelation(String sourceResourceId, String targetResourceId, RelationType type) {
        public TopologyRelation { Objects.requireNonNull(type, "type"); }
    }

    public record TopologySnapshot(
            UUID snapshotId, String targetSystemId, String version, Instant effectiveFrom, Instant effectiveTo,
            Map<String, ResourceRef> resources, List<TopologyRelation> relations) {
        public TopologySnapshot {
            snapshotId = snapshotId == null ? UUID.randomUUID() : snapshotId;
            resources = Map.copyOf(resources);
            relations = List.copyOf(relations);
        }
    }

    public interface TopologyRepository {
        TopologySnapshot save(TopologySnapshot snapshot);
        Optional<TopologySnapshot> activeAt(String targetSystemId, Instant at);
        Optional<TopologySnapshot> findVersion(String targetSystemId, String version);
    }

    public static final class TopologyViolation extends RuntimeException {
        public TopologyViolation(String code) { super(code); }
    }
}
