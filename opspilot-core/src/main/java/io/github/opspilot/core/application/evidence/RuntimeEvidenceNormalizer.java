package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.github.opspilot.core.application.evidence.EvidenceContracts.Evidence;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.EvidenceBundle;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.RuntimeObservationProvenance;

public final class RuntimeEvidenceNormalizer implements EvidenceNormalizer {
    @Override
    public EvidenceBundle normalizeRuntime(List<ObservationBatch> batches, NormalizationContext context) {
        List<Evidence> evidence = new ArrayList<>();
        for (ObservationBatch batch : batches) {
            String actualHash = ObservationContracts.sha256(batch.rawArtifact().content());
            if (!actualHash.equals(batch.rawArtifact().sha256())) {
                throw new IllegalArgumentException("OBSERVATION_BATCH_INVALID: Artifact hash mismatch");
            }
            batch.observations().forEach(observation -> evidence.add(new Evidence(
                    UUID.randomUUID(),
                    observation.signalType().name().toLowerCase(Locale.ROOT) + ".observed",
                    "RUNTIME",
                    observation.signalType(),
                    observation.resource(),
                    observation.summary(),
                    batch.query().windowStart(),
                    batch.query().windowEnd(),
                    List.of(observation.artifactId()),
                    List.of(new RuntimeObservationProvenance(
                            "RUNTIME_OBSERVATION", batch.batchId(), observation.observationId(),
                            batch.source().sourceId(), batch.rawArtifact().sha256())))));
        }
        return new EvidenceBundle(
                "1.0.0", UUID.randomUUID(), context.incidentId(), context.runId(), context.stepId(),
                Instant.now(), batches.stream().map(ObservationBatch::batchId).toList(), evidence);
    }
}
