package io.github.opspilot.core.application.state;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.AgentStepSnapshot;
import io.github.opspilot.core.domain.state.IncidentAgentState.StepAttemptSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Capacity, content and referential-integrity guard for checkpoint state. */
public final class IncidentAgentStatePolicy {
    public static final String STATE_LIMIT_EXCEEDED = "STATE_LIMIT_EXCEEDED";
    public static final String STATE_REFERENCE_INVALID = "STATE_REFERENCE_INVALID";
    public static final String STATE_CONTENT_FORBIDDEN = "STATE_CONTENT_FORBIDDEN";

    private static final List<String> FORBIDDEN = List.of(
            "stack trace", "```", "prompt:", "response:", "hidden reasoning",
            "message history", "tool raw output", "secret=", "password=", "ground truth",
            "full evidence", "artifact body", "source payload");

    private final SnapshotLimits limits;
    private final ReferenceCatalog references;

    public IncidentAgentStatePolicy(SnapshotLimits limits, ReferenceCatalog references) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.references = Objects.requireNonNull(references, "references");
    }

    public void validateBeforeSerialization(IncidentAgentState state) {
        Objects.requireNonNull(state, "state");
        checkCount(state.steps().size(), limits.maxSteps(), "steps");
        checkCount(state.evidenceIds().size(), limits.maxListSize(), "evidenceIds");
        checkCount(state.hypothesisIds().size(), limits.maxListSize(), "hypothesisIds");
        checkCount(state.missingEvidence().size(), limits.maxListSize(), "missingEvidence");
        checkCount(state.warnings().size(), limits.maxWarnings(), "warnings");
        checkCount(state.reactLoop().actionFingerprints().size(), limits.maxFingerprints(), "actionFingerprints");
        checkCount(state.reactLoop().evidenceFingerprints().size(), limits.maxFingerprints(), "evidenceFingerprints");

        List<String> text = new ArrayList<>();
        text.add(state.a2aContextId());
        text.addAll(state.missingEvidence());
        text.addAll(state.warnings());
        text.addAll(state.reactLoop().actionFingerprints());
        text.addAll(state.reactLoop().evidenceFingerprints());
        for (String value : text) {
            if (value.length() > limits.maxStringLength()) {
                throw limit("string exceeds configured limit");
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (FORBIDDEN.stream().anyMatch(normalized::contains)) {
                throw new StateContractException(STATE_CONTENT_FORBIDDEN,
                        "checkpoint contains forbidden source or reasoning content");
            }
        }

        state.evidenceIds().forEach(id -> requireReference(state, id.value(), ReferenceType.EVIDENCE));
        state.hypothesisIds().forEach(id -> requireReference(state, id.value(), ReferenceType.HYPOTHESIS));
        requireArtifact(state, state.planArtifactId());
        requireArtifact(state, state.remediationPlanArtifactId());
        requireArtifact(state, state.finalReportArtifactId());
        for (AgentStepSnapshot step : state.steps()) {
            checkCount(step.attempts().size(), limits.maxListSize(), "step attempts");
            for (StepAttemptSnapshot attempt : step.attempts()) {
                requireArtifact(state, attempt.resultArtifactId());
            }
        }
    }

    public void validateSerializedSize(byte[] json) {
        if (json.length > limits.maxSerializedBytes()) {
            throw limit("serialized checkpoint exceeds configured limit");
        }
    }

    private void requireArtifact(IncidentAgentState state, ArtifactId artifactId) {
        if (artifactId != null) {
            requireReference(state, artifactId.value(), ReferenceType.ARTIFACT);
        }
    }

    private void requireReference(IncidentAgentState state, UUID id, ReferenceType expectedType) {
        Optional<ReferenceRecord> found = references.find(id);
        if (found.isEmpty()
                || found.get().type() != expectedType
                || !found.get().runId().equals(state.runId())) {
            throw new StateContractException(STATE_REFERENCE_INVALID,
                    "checkpoint reference is missing, cross-run, or has the wrong type: " + id);
        }
    }

    private static void checkCount(int value, int maximum, String field) {
        if (value > maximum) {
            throw limit(field + " exceeds configured limit");
        }
    }

    private static StateContractException limit(String message) {
        return new StateContractException(STATE_LIMIT_EXCEEDED, message);
    }

    public record SnapshotLimits(
            int maxStringLength,
            int maxListSize,
            int maxSteps,
            int maxFingerprints,
            int maxWarnings,
            int maxSerializedBytes) {
        public SnapshotLimits {
            if (maxStringLength < 1 || maxListSize < 1 || maxSteps < 1 || maxFingerprints < 1
                    || maxWarnings < 1 || maxSerializedBytes < 1) {
                throw new IllegalArgumentException("snapshot limits must be positive");
            }
        }

        public static SnapshotLimits defaults() {
            return new SnapshotLimits(512, 256, 64, 128, 64, 262_144);
        }
    }

    public enum ReferenceType { EVIDENCE, HYPOTHESIS, ARTIFACT }

    public record ReferenceRecord(UUID id, io.github.opspilot.core.domain.identity.DomainIds.RunId runId,
                                  ReferenceType type) {
        public ReferenceRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
        }
    }

    @FunctionalInterface
    public interface ReferenceCatalog {
        Optional<ReferenceRecord> find(UUID id);
    }
}
