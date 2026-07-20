package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import io.github.opspilot.core.port.code.CodeContracts.CodeFinding;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;
import io.github.opspilot.core.port.knowledge.KnowledgeContracts.KnowledgeResult;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.github.opspilot.core.application.evidence.EvidenceContracts.Evidence;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.EvidenceBundle;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.RuntimeObservationProvenance;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.CodeProvenance;
import static io.github.opspilot.core.application.evidence.EvidenceContracts.KnowledgeProvenance;

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
                            batch.source().sourceId(), batch.rawArtifact().sha256(), null, null)))));
        }
        return new EvidenceBundle(
                "1.0.0", UUID.randomUUID(), context.incidentId(), context.runId(), context.stepId(),
                Instant.now(), batches.stream().map(ObservationBatch::batchId).toList(), evidence);
    }

    @Override
    public EvidenceBundle normalizeCode(
            CodeSnapshot snapshot, List<CodeFinding> findings, NormalizationContext context) {
        ResourceRef repository = new ResourceRef(
                snapshot.repositoryId(), ResourceType.SYSTEM, snapshot.repositoryId(), null, null, java.util.Map.of());
        List<Evidence> evidence = findings.stream().map(finding -> new Evidence(
                UUID.randomUUID(), "code." + finding.ruleId().toLowerCase(Locale.ROOT), "CODE", SignalType.CODE,
                repository, finding.summary(), snapshot.materializedAt(), snapshot.materializedAt(),
                finding.artifactIds().stream().map(id -> id.value()).toList(), List.of(new CodeProvenance(
                        "CODE_FINDING", null, stableUuid(finding.findingId()), snapshot.repositoryId(),
                        finding.fileSha256(), snapshot.revision(), finding.relativePath())))).toList();
        return new EvidenceBundle(
                "1.0.0", UUID.randomUUID(), context.incidentId(), context.runId(), context.stepId(),
                Instant.now(), List.of(), evidence);
    }

    @Override
    public EvidenceBundle normalizeKnowledge(List<KnowledgeResult> results, NormalizationContext context) {
        List<Evidence> evidence = results.stream().map(result -> new Evidence(
                UUID.randomUUID(), "knowledge.retrieved", "KNOWLEDGE", SignalType.KNOWLEDGE,
                new ResourceRef(result.knowledgeBaseId(), ResourceType.SYSTEM, result.knowledgeBaseId(),
                        null, null, java.util.Map.of()),
                result.summary(), Instant.now(), Instant.now(),
                result.artifactIds().stream().map(id -> id.value()).toList(),
                List.of(new KnowledgeProvenance(
                        "KNOWLEDGE_RESULT", null, stableUuid(result.resultId()), result.knowledgeBaseId(),
                        null, result.revision(), null)))).toList();
        return new EvidenceBundle(
                "1.0.0", UUID.randomUUID(), context.incidentId(), context.runId(), context.stepId(),
                Instant.now(), List.of(), evidence);
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
