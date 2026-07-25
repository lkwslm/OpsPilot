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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        Map<String, EvidenceAccumulator> facts = new LinkedHashMap<>();
        for (ObservationBatch batch : batches) {
            String actualHash = ObservationContracts.sha256(batch.rawArtifact().content());
            if (!actualHash.equals(batch.rawArtifact().sha256())) {
                throw new IllegalArgumentException("OBSERVATION_BATCH_INVALID: Artifact hash mismatch");
            }
            batch.observations().forEach(observation -> {
                String key = derivationKey(batch, observation.attributes(), observation.observationId());
                facts.computeIfAbsent(key, ignored -> new EvidenceAccumulator(batch, observation))
                        .add(batch, observation);
            });
        }
        List<Evidence> evidence = facts.values().stream().map(EvidenceAccumulator::evidence).toList();
        return new EvidenceBundle(
                "1.0.0", UUID.randomUUID(), context.incidentId(), context.runId(), context.stepId(),
                Instant.now(), batches.stream().map(ObservationBatch::batchId).toList(), evidence);
    }

    private static String derivationKey(ObservationBatch batch, Map<String, Object> attributes, UUID observationId) {
        Object event = attributes.get("otelEventId");
        if (event != null) return "otel:event:" + event;
        Object trace = attributes.get("otelTraceId");
        Object span = attributes.get("otelSpanId");
        if (trace != null && span != null) return "otel:span:" + trace + ":" + span;
        if (trace != null) return "otel:trace:" + trace;
        return batch.source().sourceId() + ":" + observationId;
    }

    private static final class EvidenceAccumulator {
        private final UUID evidenceId = UUID.randomUUID();
        private final ObservationBatch firstBatch;
        private final io.github.opspilot.core.port.observability.ObservationContracts.ObservationRecord first;
        private final LinkedHashSet<UUID> artifacts = new LinkedHashSet<>();
        private final List<io.github.opspilot.core.application.evidence.EvidenceContracts.ProvenanceRef> provenance =
                new ArrayList<>();

        private EvidenceAccumulator(
                ObservationBatch firstBatch,
                io.github.opspilot.core.port.observability.ObservationContracts.ObservationRecord first) {
            this.firstBatch = firstBatch;
            this.first = first;
        }

        private EvidenceAccumulator add(
                ObservationBatch batch,
                io.github.opspilot.core.port.observability.ObservationContracts.ObservationRecord observation) {
            artifacts.add(observation.artifactId());
            String topologyVersion = String.valueOf(observation.attributes().getOrDefault("topologyVersion", "unknown"));
            String location = "adapter=" + batch.source().adapterId() + "@" + batch.source().adapterVersion()
                    + ";query=" + batch.query().parameterHash() + ";topology=" + topologyVersion;
            provenance.add(new RuntimeObservationProvenance(
                    "RUNTIME_OBSERVATION", batch.batchId(), observation.observationId(),
                    observation.originSource() == null
                            ? batch.source().sourceId() : observation.originSource().sourceId(),
                    batch.rawArtifact().sha256(), null, location));
            return this;
        }

        private Evidence evidence() {
            return new Evidence(
                    evidenceId, first.signalType().name().toLowerCase(Locale.ROOT) + ".observed", "RUNTIME",
                    first.signalType(), first.resource(), first.summary(), firstBatch.query().windowStart(),
                    firstBatch.query().windowEnd(), List.copyOf(artifacts), List.copyOf(provenance));
        }
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
