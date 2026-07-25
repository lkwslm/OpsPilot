package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Fail-closed validation before an ObservationBatch may become runtime Evidence. */
public final class ObservationValidationPipeline {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|credential)\\s*[=:]\\s*[^\\s,;]+" );
    private static final Set<String> MEDIA_TYPES = Set.of(
            "application/json", "application/x-ndjson", "application/yaml", "text/plain");
    private final AuditSink audit;

    public ObservationValidationPipeline(AuditSink audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public ValidatedObservation validate(ObservationBatch batch, ValidationContext context) {
        try {
            Objects.requireNonNull(batch, "batch");
            Objects.requireNonNull(context, "context");
            validateSource(batch.source(), context);
            validateQuery(batch.query(), context);
            validateArtifact(batch, context);
            int rejected = 0;
            List<ObservationRecord> accepted = new ArrayList<>();
            for (ObservationRecord record : batch.observations()) {
                try {
                    validateRecord(batch, record, context);
                    accepted.add(record);
                } catch (ObservationValidationException invalid) {
                    rejected++;
                    if (!context.allowPartialResults()) throw invalid;
                }
            }
            if (rejected > 0 && accepted.isEmpty()) throw invalid("OBSERVATION_BATCH_INVALID");
            return new ValidatedObservation(batch, accepted, rejected, context.topologyVersion());
        } catch (RuntimeException failure) {
            String code = failure instanceof ObservationValidationException ? failure.getMessage()
                    : "OBSERVATION_BATCH_INVALID";
            audit.record(new ValidationAudit(Instant.now(), context == null ? null : context.runId(),
                    batch == null ? null : batch.batchId(), code));
            if (failure instanceof ObservationValidationException known) throw known;
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
    }

    private static void validateSource(SourceDescriptor source, ValidationContext context) {
        if (source == null || !context.readySourceIds().contains(source.sourceId())
                || !source.adapterId().equals(context.expectedAdapterId())
                || !source.adapterVersion().equals(context.expectedAdapterVersion())
                || source.connectionRef() == null
                || !source.connectionRef().matches("observability-source://[A-Za-z0-9._/-]+")) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
    }

    private static void validateQuery(ObservationQuery query, ValidationContext context) {
        if (query == null || query.resource() == null || !context.allowedResourceIds().contains(query.resource().resourceId())
                || !context.targetSystemId().equals(query.resource().systemId())
                || !context.allowedQueryTemplates().contains(query.templateId())
                || query.parameterHash() == null || !query.parameterHash().matches("sha256:[a-f0-9]{64}")
                || query.windowStart() == null || query.windowEnd() == null
                || !query.windowEnd().isAfter(query.windowStart())
                || Duration.between(query.windowStart(), query.windowEnd()).compareTo(context.maxWindow()) > 0) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
        ResourceTopologyService.validateStableIdentity(query.resource());
    }

    private static void validateArtifact(ObservationBatch batch, ValidationContext context) {
        RawArtifact artifact = batch.rawArtifact();
        if (artifact == null || artifact.content().length > context.maxArtifactBytes()
                || !MEDIA_TYPES.contains(artifact.mediaType())
                || !ObservationContracts.sha256(artifact.content()).equals(artifact.sha256())) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
        context.artifactVerifier().verify(new ArtifactClaim(
                artifact.artifactId(), context.runId(), context.taskId(), artifact.mediaType(),
                artifact.content().length, artifact.sha256()));
    }

    private static void validateRecord(
            ObservationBatch batch, ObservationRecord record, ValidationContext context) {
        if (record == null || record.resource() == null || !context.allowedResourceIds().contains(record.resource().resourceId())
                || !context.targetSystemId().equals(record.resource().systemId())
                || !record.artifactId().equals(batch.rawArtifact().artifactId())
                || record.observedAt() == null
                || record.observedAt().isBefore(batch.query().windowStart().minus(context.clockSkew()))
                || record.observedAt().isAfter(batch.query().windowEnd().plus(context.clockSkew()))
                || record.summary() == null || record.summary().length() > 4000
                || SECRET.matcher(record.summary()).find()
                || record.attributes().size() > 100
                || record.attributes().toString().length() > context.maxAttributeChars()) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
        ObservationQuality quality = record.quality();
        if (quality == null || quality.freshnessSeconds() < 0
                || quality.freshnessSeconds() > context.maxFreshness().toSeconds()
                || quality.truncated() && !context.allowPartialResults()
                || !quality.complete() && !context.allowPartialResults()
                || quality.sampleRate() != null && (quality.sampleRate() < 0 || quality.sampleRate() > 1)
                || quality.warnings().size() > 20) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
        if (record.originSource() != null && record.originSource().sourceId().isBlank()) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
        if (batch.source().sourceKind() == SourceKind.CUSTOM && record.originSource() == null) {
            throw invalid("OBSERVATION_BATCH_INVALID");
        }
    }

    private static ObservationValidationException invalid(String code) {
        return new ObservationValidationException(code);
    }

    public record ValidationContext(
            String targetSystemId, UUID runId, UUID taskId, Set<String> allowedResourceIds,
            Set<String> readySourceIds, String expectedAdapterId, String expectedAdapterVersion,
            Set<String> allowedQueryTemplates, String topologyVersion, Duration maxWindow,
            Duration clockSkew, Duration maxFreshness, int maxAttributeChars, int maxArtifactBytes,
            boolean allowPartialResults, ArtifactVerifier artifactVerifier) {
        public ValidationContext {
            allowedResourceIds = Set.copyOf(allowedResourceIds);
            readySourceIds = Set.copyOf(readySourceIds);
            allowedQueryTemplates = Set.copyOf(allowedQueryTemplates);
            Objects.requireNonNull(artifactVerifier, "artifactVerifier");
        }
    }

    public record ValidatedObservation(
            ObservationBatch batch, List<ObservationRecord> acceptedRecords,
            int rejectedCount, String topologyVersion) {
        public ValidatedObservation { acceptedRecords = List.copyOf(acceptedRecords); }

        public ObservationBatch canonicalBatch() {
            List<ObservationRecord> records = acceptedRecords.stream().map(record -> {
                Map<String, Object> attributes = new HashMap<>(record.attributes());
                attributes.put("topologyVersion", topologyVersion);
                return new ObservationRecord(
                        record.observationId(), record.signalType(), record.resource(), record.observedAt(),
                        record.summary(), record.artifactId(), attributes, record.quality(),
                        record.originSource(), record.upstreamRequestId());
            }).toList();
            return new ObservationBatch(batch.schemaVersion(), batch.batchId(), batch.source(), batch.query(),
                    batch.collectedAt(), batch.upstreamRequestId(), records, batch.rawArtifact());
        }
    }

    public record ArtifactClaim(
            UUID artifactId, UUID runId, UUID taskId, String mediaType, long sizeBytes, String sha256) { }

    @FunctionalInterface public interface ArtifactVerifier { void verify(ArtifactClaim claim); }
    @FunctionalInterface public interface AuditSink { void record(ValidationAudit audit); }
    public record ValidationAudit(Instant occurredAt, UUID runId, UUID batchId, String outcomeCode) { }

    public static final class ObservationValidationException extends RuntimeException {
        public ObservationValidationException(String code) { super(code); }
    }
}
