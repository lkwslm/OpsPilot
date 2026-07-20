package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Diagnosis, hypothesis and RCA inputs are references only. */
public final class EvidenceOnlyAnalysis {
    private static final Set<String> ALLOWED_SCHEMA_FIELDS = Set.of("evidenceIds");

    private EvidenceOnlyAnalysis() {
    }

    public record DiagnosisInput(List<EvidenceId> evidenceIds) {
        public DiagnosisInput { evidenceIds = requireEvidence(evidenceIds); }
    }

    public record HypothesisInput(List<EvidenceId> evidenceIds) {
        public HypothesisInput { evidenceIds = requireEvidence(evidenceIds); }
    }

    public record RcaInput(List<EvidenceId> evidenceIds) {
        public RcaInput { evidenceIds = requireEvidence(evidenceIds); }
    }

    public static DiagnosisInput parseDiagnosis(Map<String, ?> schemaInput) {
        if (!ALLOWED_SCHEMA_FIELDS.equals(schemaInput.keySet()) || !(schemaInput.get("evidenceIds") instanceof List<?> ids)) {
            throw new IllegalArgumentException("ANALYSIS_INPUT_INVALID");
        }
        List<EvidenceId> values = ids.stream().map(value -> EvidenceId.parse((String) value)).toList();
        return new DiagnosisInput(values);
    }

    private static List<EvidenceId> requireEvidence(List<EvidenceId> ids) {
        List<EvidenceId> copy = List.copyOf(ids);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("analysis requires Evidence IDs");
        }
        return copy;
    }
}
