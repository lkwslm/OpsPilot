package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.artifact.ArtifactPort;
import io.github.opspilot.core.port.observability.ObservationContracts.*;

import java.util.List;

/** Stores raw source bytes before returning a Batch that is eligible for normalization. */
public final class ObservationIngestionService {
    private final ArtifactPort artifacts;
    private final ObservationValidationPipeline validation;

    public ObservationIngestionService(ArtifactPort artifacts, ObservationValidationPipeline validation) {
        this.artifacts = artifacts;
        this.validation = validation;
    }

    public ObservationValidationPipeline.ValidatedObservation ingest(
            ObservationBatch batch, ObservationValidationPipeline.ValidationContext context) {
        var storedId = artifacts.store(new RunId(context.runId()),
                batch.rawArtifact().mediaType(), batch.rawArtifact().content()).value();
        RawArtifact stored = new RawArtifact(storedId, batch.rawArtifact().mediaType(),
                batch.rawArtifact().sha256(), batch.rawArtifact().content());
        List<ObservationRecord> rebound = batch.observations().stream().map(record -> new ObservationRecord(
                record.observationId(), record.signalType(), record.resource(), record.observedAt(), record.summary(),
                storedId, record.attributes(), record.quality(), record.originSource(), record.upstreamRequestId())).toList();
        ObservationBatch persisted = new ObservationBatch(
                batch.schemaVersion(), batch.batchId(), batch.source(), batch.query(), batch.collectedAt(),
                batch.upstreamRequestId(), rebound, stored);
        return validation.validate(persisted, context);
    }
}
