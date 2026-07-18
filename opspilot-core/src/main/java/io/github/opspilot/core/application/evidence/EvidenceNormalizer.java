package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;

import java.util.List;

import static io.github.opspilot.core.application.evidence.EvidenceContracts.EvidenceBundle;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;

public interface EvidenceNormalizer {
    EvidenceBundle normalizeRuntime(List<ObservationBatch> batches, NormalizationContext context);
}
