package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvidenceContracts {
    private EvidenceContracts() {
    }

    public record NormalizationContext(UUID incidentId, UUID runId, UUID stepId) {
    }

    public record RuntimeObservationProvenance(
            String kind,
            UUID batchId,
            UUID observationId,
            String sourceId,
            String artifactSha256) {
        public RuntimeObservationProvenance {
            if (!"RUNTIME_OBSERVATION".equals(kind)) {
                throw new IllegalArgumentException("Invalid runtime provenance kind");
            }
        }
    }

    public record Evidence(
            UUID evidenceId,
            String evidenceCode,
            String factOrigin,
            SignalType signalType,
            ResourceRef resource,
            String claim,
            Instant windowStart,
            Instant windowEnd,
            List<UUID> artifactIds,
            List<RuntimeObservationProvenance> provenanceRefs) {
        public Evidence {
            artifactIds = List.copyOf(artifactIds);
            provenanceRefs = List.copyOf(provenanceRefs);
        }
    }

    public record EvidenceBundle(
            String schemaVersion,
            UUID bundleId,
            UUID incidentId,
            UUID runId,
            UUID stepId,
            Instant generatedAt,
            List<UUID> observationBatchIds,
            List<Evidence> evidence) {
        public EvidenceBundle {
            observationBatchIds = List.copyOf(observationBatchIds);
            evidence = List.copyOf(evidence);
        }
    }
}
