package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.application.evidence.ArtifactReceiver.EvidenceWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.HypothesisWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RelationWrite;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.RemoteArtifact;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.ValidationPort;
import io.github.opspilot.core.application.evidence.ArtifactReceiver.VerificationWrite;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Immutable ownership and permission snapshot used by the ordered receiver. */
public final class ArtifactValidationPolicy implements ValidationPort {
    private final ValidationContext context;
    private final JsonSchemaValidator schema;

    public ArtifactValidationPolicy(ValidationContext context, JsonSchemaValidator schema) {
        this.context = Objects.requireNonNull(context, "context");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public boolean jsonSchemaValid(RemoteArtifact artifact) {
        return schema.valid(artifact.schemaVersion(), artifact.payload());
    }

    @Override
    public boolean sourceOwnedByRun(RemoteArtifact artifact) {
        return artifact.runId().equals(context.runId())
                && artifact.runId().equals(context.sourceOwners().get(artifact.sourceId()));
    }

    @Override
    public boolean resourceTaskRunOwned(RemoteArtifact artifact) {
        return artifact.runId().equals(context.resourceOwners().get(artifact.resourceId()))
                && artifact.runId().equals(context.taskOwners().get(artifact.taskId()))
                && context.expectedTaskId().equals(artifact.taskId())
                && context.expectedArtifactId().equals(artifact.artifactId());
    }

    @Override
    public boolean referencesAuthorized(RemoteArtifact artifact) {
        Set<EvidenceId> evidence = artifact.mutation().hypotheses().stream()
                .flatMap(value -> value.evidenceIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        return context.authorizedEvidence().containsAll(evidence)
                && context.authorizedArtifacts().contains(artifact.artifactId());
    }

    @Override
    public boolean domainInvariantsValid(RemoteArtifact artifact) {
        if (!artifact.artifactId().equals(artifact.mutation().artifactId())
                || !artifact.runId().equals(artifact.mutation().runId())
                || artifact.mutation().outboxEventId() == null) {
            return false;
        }
        Set<EvidenceId> evidence = new java.util.HashSet<>(artifact.currentRunEvidence());
        evidence.addAll(artifact.mutation().evidence().stream().map(EvidenceWrite::evidenceId).toList());
        Set<HypothesisId> hypotheses = artifact.mutation().hypotheses().stream()
                .map(HypothesisWrite::hypothesisId).collect(Collectors.toUnmodifiableSet());
        return artifact.mutation().relations().stream().allMatch(
                        relation -> relationValid(relation, hypotheses, evidence))
                && artifact.mutation().verifications().stream().allMatch(
                        verification -> verificationValid(verification, hypotheses, evidence));
    }

    private static boolean relationValid(
            RelationWrite relation, Set<HypothesisId> hypotheses, Set<EvidenceId> evidence) {
        return hypotheses.contains(relation.hypothesisId())
                && evidence.contains(relation.evidenceId())
                && Set.of("SUPPORTS", "CONFLICTS").contains(relation.relation());
    }

    private static boolean verificationValid(
            VerificationWrite verification, Set<HypothesisId> hypotheses, Set<EvidenceId> evidence) {
        return hypotheses.contains(verification.hypothesisId())
                && (verification.evidenceId() == null || evidence.contains(verification.evidenceId()))
                && Set.of("CONFIRMED", "REFUTED", "INCONCLUSIVE").contains(verification.result());
    }

    @FunctionalInterface
    public interface JsonSchemaValidator {
        boolean valid(String schemaVersion, byte[] payload);
    }

    public record ValidationContext(
            RunId runId, UUID expectedTaskId, ArtifactId expectedArtifactId,
            Map<String, RunId> sourceOwners, Map<String, RunId> resourceOwners,
            Map<UUID, RunId> taskOwners, Set<EvidenceId> authorizedEvidence,
            Set<ArtifactId> authorizedArtifacts) {
        public ValidationContext {
            sourceOwners = Map.copyOf(sourceOwners);
            resourceOwners = Map.copyOf(resourceOwners);
            taskOwners = Map.copyOf(taskOwners);
            authorizedEvidence = Set.copyOf(authorizedEvidence);
            authorizedArtifacts = Set.copyOf(authorizedArtifacts);
        }
    }
}
