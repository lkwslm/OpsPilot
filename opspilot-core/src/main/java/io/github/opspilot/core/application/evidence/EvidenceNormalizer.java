package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import io.github.opspilot.core.port.code.CodeContracts.CodeFinding;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;
import io.github.opspilot.core.port.knowledge.KnowledgeContracts.KnowledgeResult;

import java.util.List;

import static io.github.opspilot.core.application.evidence.EvidenceContracts.EvidenceBundle;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;

public interface EvidenceNormalizer {
    EvidenceBundle normalizeRuntime(List<ObservationBatch> batches, NormalizationContext context);

    EvidenceBundle normalizeCode(CodeSnapshot snapshot, List<CodeFinding> findings, NormalizationContext context);

    EvidenceBundle normalizeKnowledge(List<KnowledgeResult> results, NormalizationContext context);
}
