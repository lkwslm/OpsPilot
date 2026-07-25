package io.github.opspilot.core.observability;

import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.application.observability.ObservationValidationPipeline;
import io.github.opspilot.core.application.observability.ObservationValidationPipeline.*;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class ObservationPipelineAndEvidenceTest {
    private static final Instant START = Instant.parse("2026-07-23T01:00:00Z");
    private static final ResourceRef RESOURCE = new ResourceRef(
            "service:order-service", ResourceType.SERVICE, "sample-commerce", "order-service", "local", Map.of());

    @Test
    void pipelineAcceptsValidBatchAndBindsTopologyAndArtifactClaim() {
        List<ArtifactClaim> claims = new ArrayList<>();
        List<ValidationAudit> audits = new ArrayList<>();
        ObservationValidationPipeline pipeline = new ObservationValidationPipeline(audits::add);
        ObservationBatch batch = batch("prom", SourceKind.PROMETHEUS, "trace-1", RESOURCE, "safe summary");
        ValidatedObservation validated = pipeline.validate(batch, context(false, claims::add));

        assertEquals(1, validated.acceptedRecords().size());
        assertEquals("sample-compose/1.0.0",
                validated.canonicalBatch().observations().getFirst().attributes().get("topologyVersion"));
        assertEquals(batch.rawArtifact().artifactId(), claims.getFirst().artifactId());
        assertTrue(audits.isEmpty());
    }

    @Test
    void pipelineFailsClosedForScopeTimeQualitySecretAndArtifactIntegrity() {
        List<ValidationAudit> audits = new ArrayList<>();
        ObservationValidationPipeline pipeline = new ObservationValidationPipeline(audits::add);

        ResourceRef otherTarget = new ResourceRef("service:order-service", ResourceType.SERVICE,
                "other-target", "order-service", "local", Map.of());
        assertInvalid(pipeline, batch("prom", SourceKind.PROMETHEUS, "trace-1", otherTarget, "safe"));
        assertInvalid(pipeline, batch("prom", SourceKind.PROMETHEUS, "trace-1", RESOURCE, "token=leaked"));

        ObservationBatch valid = batch("prom", SourceKind.PROMETHEUS, "trace-1", RESOURCE, "safe");
        RawArtifact corrupt = new RawArtifact(valid.rawArtifact().artifactId(), "application/json",
                "sha256:" + "0".repeat(64), valid.rawArtifact().content());
        assertInvalid(pipeline, new ObservationBatch("1.0.0", valid.batchId(), valid.source(), valid.query(),
                valid.collectedAt(), null, valid.observations(), corrupt));
        assertEquals(3, audits.size());
    }

    @Test
    void explicitPartialContractKeepsOnlyValidRecordsAndReportsRejectedCount() {
        ObservationBatch first = batch("prom", SourceKind.PROMETHEUS, "trace-1", RESOURCE, "safe");
        ObservationRecord invalid = new ObservationRecord(UUID.randomUUID(), SignalType.METRIC, RESOURCE,
                START, "password=bad", first.rawArtifact().artifactId(), Map.of(),
                new ObservationQuality(1, false, true, 0.5, List.of("partial")));
        ObservationBatch mixed = new ObservationBatch("1.0.0", first.batchId(), first.source(), first.query(),
                first.collectedAt(), null, List.of(first.observations().getFirst(), invalid), first.rawArtifact());
        ValidatedObservation result = new ObservationValidationPipeline(audit -> { })
                .validate(mixed, context(true, claim -> { }));
        assertEquals(1, result.acceptedRecords().size());
        assertEquals(1, result.rejectedCount());
    }

    @Test
    void sameOtelDerivedFactAcrossPrometheusAndJaegerCountsOnceButKeepsBothProvenances() {
        ObservationBatch prometheus = batch("prom", SourceKind.PROMETHEUS, "shared-trace", RESOURCE, "latency high");
        ObservationBatch jaeger = batch("jaeger", SourceKind.JAEGER, "shared-trace", RESOURCE, "span slow");
        var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(List.of(prometheus, jaeger),
                new NormalizationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(1, bundle.evidence().size());
        assertEquals(2, bundle.evidence().getFirst().provenanceRefs().size());
        assertEquals(2, bundle.evidence().getFirst().artifactIds().size());
        assertTrue(bundle.evidence().getFirst().provenanceRefs().stream()
                .allMatch(ref -> ref.location().contains("adapter=") && ref.location().contains("query=")));
    }

    private static void assertInvalid(ObservationValidationPipeline pipeline, ObservationBatch batch) {
        ObservationValidationException failure = assertThrows(ObservationValidationException.class,
                () -> pipeline.validate(batch, context(false, claim -> { })));
        assertEquals("OBSERVATION_BATCH_INVALID", failure.getMessage());
    }

    private static ValidationContext context(boolean partial, ArtifactVerifier verifier) {
        return new ValidationContext("sample-commerce", UUID.randomUUID(), UUID.randomUUID(),
                Set.of(RESOURCE.resourceId()), Set.of("prom", "jaeger"),
                "adapter-prom", "1.0.0", Set.of("metric/http-v1"), "sample-compose/1.0.0",
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofHours(1),
                1000, 100_000, partial, verifier);
    }

    private static ObservationBatch batch(
            String sourceId, SourceKind kind, String traceId, ResourceRef resource, String summary) {
        byte[] content = ("{\"source\":\"" + sourceId + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RawArtifact artifact = new RawArtifact(UUID.randomUUID(), "application/json",
                ObservationContracts.sha256(content), content);
        SourceDescriptor source = new SourceDescriptor(sourceId, kind, "adapter-prom", "1.0.0",
                "observability-source://sample/" + sourceId, "local", Map.of(), Set.of(SignalType.METRIC));
        ObservationQuery query = new ObservationQuery("metric/http-v1", ObservationContracts.sha256("safe"),
                START.minusSeconds(30), START.plusSeconds(30), resource);
        ObservationRecord record = new ObservationRecord(UUID.randomUUID(), SignalType.METRIC, resource,
                START, summary, artifact.artifactId(), Map.of("otelTraceId", traceId, "topologyVersion", "v1"),
                new ObservationQuality(1, true, false, 1.0, List.of()));
        return new ObservationBatch("1.0.0", UUID.randomUUID(), source, query, START.plusSeconds(1), null,
                List.of(record), artifact);
    }
}
