package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates remote artifacts in a fixed short-circuit order, then commits once. */
public final class ArtifactReceiver {
    public static final String EXPECTED_MEDIA_TYPE = "application/vnd.opspilot.artifact+json";
    public static final int SUPPORTED_SCHEMA_MAJOR = 1;

    private final ValidationPort validation;
    private final ReceptionUnitOfWork unitOfWork;

    public ArtifactReceiver(ValidationPort validation, ReceptionUnitOfWork unitOfWork) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    public ReceptionResult receive(RemoteArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        require(EXPECTED_MEDIA_TYPE.equals(artifact.mediaType()), Layer.MEDIA_TYPE);
        require(schemaMajor(artifact.schemaVersion()) == SUPPORTED_SCHEMA_MAJOR, Layer.SCHEMA_MAJOR);
        require(validation.jsonSchemaValid(artifact), Layer.JSON_SCHEMA);
        require(validation.sourceOwnedByRun(artifact), Layer.SOURCE_OWNERSHIP);
        require(validation.resourceTaskRunOwned(artifact), Layer.RESOURCE_TASK_RUN_OWNERSHIP);
        require(digest(artifact.payload()).equals(artifact.sha256()), Layer.ARTIFACT_SHA256);
        require(validation.referencesAuthorized(artifact), Layer.REFERENCE_PERMISSION);
        require(validation.domainInvariantsValid(artifact), Layer.DOMAIN_INVARIANT);
        validateAnalysisFacts(artifact);
        unitOfWork.commit(artifact);
        return new ReceptionResult(artifact.artifactId(), "ARTIFACT_ACCEPTED");
    }

    private static void validateAnalysisFacts(RemoteArtifact artifact) {
        Set<EvidenceId> current = Set.copyOf(artifact.currentRunEvidence());
        for (HypothesisWrite hypothesis : artifact.mutation().hypotheses()) {
            if (hypothesis.evidenceIds().isEmpty() || !current.containsAll(hypothesis.evidenceIds())) {
                throw new ValidationFailure(Layer.DOMAIN_INVARIANT, "ANALYSIS_EVIDENCE_WRONG_RUN");
            }
        }
        if (artifact.mutation().rawFactKinds().stream().anyMatch(kind -> kind != RawFactKind.EVIDENCE)) {
            throw new ValidationFailure(Layer.DOMAIN_INVARIANT, "UNNORMALIZED_FACT_FORBIDDEN");
        }
    }

    private static void require(boolean valid, Layer layer) {
        if (!valid) {
            throw new ValidationFailure(layer, layer.errorCode);
        }
    }

    private static int schemaMajor(String version) {
        try {
            return Integer.parseInt(version.split("\\.", 2)[0]);
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    public static String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public interface ValidationPort {
        boolean jsonSchemaValid(RemoteArtifact artifact);
        boolean sourceOwnedByRun(RemoteArtifact artifact);
        boolean resourceTaskRunOwned(RemoteArtifact artifact);
        boolean referencesAuthorized(RemoteArtifact artifact);
        boolean domainInvariantsValid(RemoteArtifact artifact);
    }

    @FunctionalInterface
    public interface ReceptionUnitOfWork {
        /** Artifact, facts, relations, verifications and outbox are one transaction. */
        void commit(RemoteArtifact artifact);
    }

    public enum Layer {
        MEDIA_TYPE("ARTIFACT_MEDIA_TYPE_INVALID"),
        SCHEMA_MAJOR("ARTIFACT_SCHEMA_MAJOR_UNSUPPORTED"),
        JSON_SCHEMA("ARTIFACT_SCHEMA_INVALID"),
        SOURCE_OWNERSHIP("ARTIFACT_SOURCE_WRONG_RUN"),
        RESOURCE_TASK_RUN_OWNERSHIP("ARTIFACT_OWNERSHIP_INVALID"),
        ARTIFACT_SHA256("ARTIFACT_HASH_MISMATCH"),
        REFERENCE_PERMISSION("ARTIFACT_REFERENCE_FORBIDDEN"),
        DOMAIN_INVARIANT("ARTIFACT_DOMAIN_INVALID");

        private final String errorCode;
        Layer(String errorCode) { this.errorCode = errorCode; }
    }

    public enum RawFactKind { EVIDENCE, OBSERVATION, CODE_FINDING, KNOWLEDGE_RESULT }

    public record RemoteArtifact(
            ArtifactId artifactId, RunId runId, UUID taskId, String sourceId, String resourceId,
            String mediaType, String schemaVersion, String sha256, byte[] payload,
            List<EvidenceId> currentRunEvidence, ReceptionMutation mutation) {
        public RemoteArtifact {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(resourceId, "resourceId");
            payload = payload.clone();
            currentRunEvidence = List.copyOf(currentRunEvidence);
            Objects.requireNonNull(mutation, "mutation");
        }

        public static RemoteArtifact json(
                ArtifactId artifactId, RunId runId, UUID taskId, byte[] payload,
                List<EvidenceId> evidence, ReceptionMutation mutation) {
            return new RemoteArtifact(artifactId, runId, taskId, "source", "resource",
                    EXPECTED_MEDIA_TYPE, "1.0.0", digest(payload), payload, evidence, mutation);
        }
    }

    public record ReceptionMutation(
            ArtifactId artifactId, RunId runId, List<EvidenceWrite> evidence,
            List<HypothesisWrite> hypotheses, List<RelationWrite> relations,
            List<VerificationWrite> verifications, List<RawFactKind> rawFactKinds,
            UUID outboxEventId) {
        public ReceptionMutation {
            evidence = List.copyOf(evidence);
            hypotheses = List.copyOf(hypotheses);
            relations = List.copyOf(relations);
            verifications = List.copyOf(verifications);
            rawFactKinds = List.copyOf(rawFactKinds);
        }
    }

    public record EvidenceWrite(
            EvidenceId evidenceId, String evidenceCode, String summary,
            java.time.Instant observedAt, String sourceType) {
        public EvidenceWrite(EvidenceId evidenceId, String evidenceCode, String summary,
                java.time.Instant observedAt) {
            this(evidenceId, evidenceCode, summary, observedAt, "ARTIFACT");
        }
        /** Compatibility constructor for pre-Phase-7 producers that do not yet emit a normalized code. */
        public EvidenceWrite(EvidenceId evidenceId, String summary) {
            this(evidenceId, null, summary, null, "ARTIFACT");
        }
    }
    public record HypothesisWrite(HypothesisId hypothesisId, String statement, List<EvidenceId> evidenceIds) {
        public HypothesisWrite { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record RelationWrite(HypothesisId hypothesisId, EvidenceId evidenceId, String relation) { }
    public record VerificationWrite(UUID verificationId, HypothesisId hypothesisId,
                                    EvidenceId evidenceId, String result, String summary) { }
    public record ReceptionResult(ArtifactId artifactId, String outcomeCode) { }

    public static final class ValidationFailure extends RuntimeException {
        private final Layer layer;
        private final String errorCode;

        public ValidationFailure(Layer layer, String errorCode) {
            super(errorCode);
            this.layer = layer;
            this.errorCode = errorCode;
        }

        public Layer layer() { return layer; }
        public String errorCode() { return errorCode; }
    }
}
