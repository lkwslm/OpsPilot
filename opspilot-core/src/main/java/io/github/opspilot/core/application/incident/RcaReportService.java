package io.github.opspilot.core.application.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Reads one sealed version briefly, then generates and renders one RCA object outside the transaction. */
public final class RcaReportService {
    private final SealedAnalysisReader reader;
    private final ReportModel model;
    private final ObjectMapper json;

    public RcaReportService(SealedAnalysisReader reader, ReportModel model, ObjectMapper json) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.model = Objects.requireNonNull(model, "model");
        this.json = Objects.requireNonNull(json, "json");
    }

    public RenderedReport generate(RunId runId, long runVersion) {
        SealedAnalysis input = reader.inReadOnlyTransaction(runId, runVersion);
        String inputDigest = digest(write(input));
        StructuredRca rca = model.generate(input);
        validate(rca, input);
        String jsonArtifact = write(rca);
        String markdownArtifact = renderMarkdown(rca);
        return new RenderedReport(inputDigest, rca, jsonArtifact, markdownArtifact);
    }

    private static void validate(StructuredRca rca, SealedAnalysis input) {
        if (rca.rootCauseCode() == null || rca.rootCauseCode().isBlank()) {
            throw new IllegalArgumentException("RCA_ROOT_CAUSE_CODE_REQUIRED");
        }
        Set<EvidenceId> available = input.evidence().stream().map(EvidenceView::evidenceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (rca.evidenceIds().isEmpty() || !available.containsAll(rca.evidenceIds())) {
            throw new IllegalArgumentException("RCA_EVIDENCE_REFERENCE_INVALID");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RCA_SERIALIZATION_FAILED", exception);
        }
    }

    private static String renderMarkdown(StructuredRca rca) {
        return "# " + rca.rootCauseCode() + "\n\n" + rca.summary() + "\n\nEvidence: "
                + rca.evidenceIds().stream().map(EvidenceId::wire).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    public interface SealedAnalysisReader {
        /** The implementation opens and closes a short, read-only consistent transaction here. */
        SealedAnalysis inReadOnlyTransaction(RunId runId, long runVersion);
    }

    @FunctionalInterface
    public interface ReportModel {
        StructuredRca generate(SealedAnalysis input);
    }

    public record SealedAnalysis(
            RunId runId, long runVersion, List<EvidenceView> evidence,
            List<HypothesisView> hypotheses, List<RelationView> relations,
            List<VerificationView> verifications, List<String> missingEvidence) {
        public SealedAnalysis {
            evidence = List.copyOf(evidence);
            hypotheses = List.copyOf(hypotheses);
            relations = List.copyOf(relations);
            verifications = List.copyOf(verifications);
            missingEvidence = List.copyOf(missingEvidence);
        }
    }

    public record EvidenceView(EvidenceId evidenceId, String summary) { }
    public record HypothesisView(HypothesisId hypothesisId, String statement, String status) { }
    public record RelationView(HypothesisId hypothesisId, EvidenceId evidenceId, String relation) { }
    public record VerificationView(HypothesisId hypothesisId, EvidenceId evidenceId, String result) { }
    public record StructuredRca(String rootCauseCode, String summary, List<EvidenceId> evidenceIds, String conclusion) {
        public StructuredRca { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record RenderedReport(
            String inputDigest, StructuredRca rca, String jsonArtifact, String markdownArtifact) { }
}
