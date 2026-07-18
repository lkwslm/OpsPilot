package io.github.opspilot.core.port.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Stable, vendor-neutral contracts at the Source/Observation boundary. */
public final class ObservationContracts {
    private ObservationContracts() {
    }

    public enum SourceKind { PROMETHEUS, JAEGER, FILE, HTTP, CUSTOM }

    public enum SignalType { LOG, METRIC, TRACE, EVENT, HEALTH, CONFIG, TOPOLOGY }

    public enum ResourceType {
        SYSTEM, SERVICE, INSTANCE, ENDPOINT, POD, CONTAINER, NODE, CLUSTER, NAMESPACE,
        DATASTORE, QUEUE, EXTERNAL_DEPENDENCY
    }

    public record SourceDescriptor(
            String sourceId,
            SourceKind sourceKind,
            String adapterId,
            String adapterVersion,
            String connectionRef,
            String environment,
            Map<String, String> scope,
            Set<SignalType> capabilities) {
        public SourceDescriptor {
            scope = Map.copyOf(scope);
            capabilities = Set.copyOf(capabilities);
        }
    }

    public record ResourceRef(
            String resourceId,
            ResourceType resourceType,
            String systemId,
            String serviceName,
            String environment,
            Map<String, String> attributes) {
        public ResourceRef {
            attributes = Map.copyOf(attributes);
        }
    }

    public record ObservationQuery(
            String templateId,
            String parameterHash,
            Instant windowStart,
            Instant windowEnd,
            ResourceRef resource) {
    }

    public record SourceExecutionContext(
            Instant deadline,
            boolean authorized,
            BooleanSupplier cancelled,
            String expectedArtifactSha256) {
        public SourceExecutionContext {
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(cancelled, "cancelled");
        }

        public static SourceExecutionContext authorizedUntil(Instant deadline) {
            return new SourceExecutionContext(deadline, true, () -> false, null);
        }
    }

    public record RawArtifact(UUID artifactId, String mediaType, String sha256, byte[] content) {
        public RawArtifact {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record ObservationQuality(
            long freshnessSeconds,
            boolean complete,
            boolean truncated,
            Double sampleRate,
            List<String> warnings) {
        public ObservationQuality {
            warnings = List.copyOf(warnings);
        }
    }

    public record ObservationRecord(
            UUID observationId,
            SignalType signalType,
            ResourceRef resource,
            Instant observedAt,
            String summary,
            UUID artifactId,
            Map<String, Object> attributes,
            ObservationQuality quality) {
        public ObservationRecord {
            attributes = Map.copyOf(attributes);
        }
    }

    public record ObservationBatch(
            String schemaVersion,
            UUID batchId,
            SourceDescriptor source,
            ObservationQuery query,
            Instant collectedAt,
            String upstreamRequestId,
            List<ObservationRecord> observations,
            RawArtifact rawArtifact) {
        public ObservationBatch {
            observations = List.copyOf(observations);
            if (!"1.0.0".equals(schemaVersion)) {
                throw new IllegalArgumentException("Unsupported ObservationBatch schema version");
            }
            if (!rawArtifact.artifactId().equals(observations.isEmpty()
                    ? rawArtifact.artifactId() : observations.getFirst().artifactId())) {
                throw new IllegalArgumentException("Observation Artifact does not belong to this batch");
            }
        }
    }

    public static String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
