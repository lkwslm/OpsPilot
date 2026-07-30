package io.github.opspilot.core.application.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.HypothesisId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads one sealed version briefly, then validates and renders one RCA object outside the transaction. */
public final class RcaReportService {
    private static final Set<String> OUTCOMES = Set.of("CONCLUSIVE", "PARTIAL", "INCONCLUSIVE");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> ACTION_SECTIONS =
            Set.of("immediate", "longTerm", "monitoring", "tests", "humanNextSteps", "rollback");
    private final SealedAnalysisReader reader;
    private final ReportModel model;
    private final RcaMetadataWriter metadataWriter;
    private final ObjectMapper json;

    public RcaReportService(SealedAnalysisReader reader, ReportModel model, ObjectMapper json) {
        this(reader, model, (ignored, jsonArtifact, markdownArtifact) -> { }, json);
    }

    public RcaReportService(
            SealedAnalysisReader reader, ReportModel model,
            RcaMetadataWriter metadataWriter, ObjectMapper json) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.model = Objects.requireNonNull(model, "model");
        this.metadataWriter = Objects.requireNonNull(metadataWriter, "metadataWriter");
        this.json = Objects.requireNonNull(json, "json");
    }

    public RenderedReport generate(RunId runId, long runVersion) {
        SealedAnalysis input = reader.inReadOnlyTransaction(runId, runVersion);
        validateSealedInput(input, runId, runVersion);
        String inputDigest = digest(write(input));
        StructuredRca rca = model.generate(input);
        validate(rca, input);
        String jsonArtifact = write(rca);
        String objectDigest = digest(jsonArtifact);
        String markdownArtifact = renderMarkdown(rca, objectDigest);
        metadataWriter.save(new RcaMetadata(
                input.runId(), input.runVersion(), input.analysisSealedAt(),
                rca.schemaVersion(), inputDigest, objectDigest, digest(markdownArtifact)),
                jsonArtifact, markdownArtifact);
        return new RenderedReport(inputDigest, objectDigest, rca, jsonArtifact, markdownArtifact);
    }

    private static void validateSealedInput(SealedAnalysis input, RunId runId, long runVersion) {
        if (!input.runId().equals(runId) || input.runVersion() != runVersion
                || !"GENERATING_REPORT".equals(input.runStatus()) || input.analysisSealedAt() == null) {
            throw new IllegalStateException("RCA_RUN_NOT_SEALED_FOR_REPORT");
        }
    }

    private static void validate(StructuredRca rca, SealedAnalysis input) {
        Objects.requireNonNull(rca, "rca");
        if (!"1.0.0".equals(rca.schemaVersion())
                || !input.incidentId().wire().equals(rca.incidentId())
                || !input.runId().wire().equals(rca.runId())) {
            throw new IllegalArgumentException("RCA_IDENTITY_OR_SCHEMA_INVALID");
        }
        if (!OUTCOMES.contains(rca.outcome()) || !SEVERITIES.contains(rca.severity())
                || rca.summary().isBlank() || rca.hypotheses().isEmpty()
                || !rca.actions().keySet().equals(ACTION_SECTIONS)) {
            throw new IllegalArgumentException("RCA_STRUCTURE_INVALID");
        }
        if ("INCONCLUSIVE".equals(rca.outcome()) && rca.rootCause() != null) {
            throw new IllegalArgumentException("RCA_INCONCLUSIVE_ROOT_CAUSE_FORBIDDEN");
        }
        if (!"INCONCLUSIVE".equals(rca.outcome()) && rca.rootCause() == null) {
            throw new IllegalArgumentException("RCA_ROOT_CAUSE_REQUIRED");
        }
        if (rca.rootCause() != null && (rca.rootCause().rootCauseCode() == null
                || !rca.rootCause().rootCauseCode().matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$"))) {
            throw new IllegalArgumentException("RCA_ROOT_CAUSE_CODE_INVALID");
        }
        Map<String, EvidenceView> available = input.evidence().stream()
                .collect(Collectors.toUnmodifiableMap(view -> view.evidenceId().wire(), Function.identity()));
        for (Citation citation : rca.citations()) {
            EvidenceView evidence = available.get(citation.evidenceId());
            if (evidence == null
                    || citation.evidenceCode() == null || citation.evidenceCode().isBlank()
                    || evidence.artifactId() == null
                    || !evidence.artifactId().wire().equals(citation.artifactId())
                    || !Objects.equals(evidence.evidenceCode(), citation.evidenceCode())
                    || !evidence.callerCanAccess()
                    || evidence.artifactSha256() == null
                    || !evidence.artifactSha256().matches("[0-9a-f]{64}")
                    || evidence.observedAt() == null
                    || evidence.observedAt().isBefore(input.incidentStartedAt())
                    || evidence.observedAt().isAfter(input.incidentEndedAt())) {
                throw new IllegalArgumentException("RCA_CITATION_INVALID");
            }
        }
        Set<String> cited = rca.citations().stream().map(Citation::evidenceId).collect(Collectors.toUnmodifiableSet());
        if (rca.rootCause() != null && (rca.rootCause().supportingEvidenceIds().isEmpty()
                || !cited.containsAll(rca.rootCause().supportingEvidenceIds())
                || !cited.containsAll(rca.rootCause().conflictingEvidenceIds()))) {
            throw new IllegalArgumentException("RCA_ROOT_CAUSE_EVIDENCE_INVALID");
        }
        for (HypothesisResult hypothesis : rca.hypotheses()) {
            if (!Set.of("SUPPORTED", "REFUTED", "UNVERIFIED").contains(hypothesis.status())
                    || hypothesis.confidence() < 0 || hypothesis.confidence() > 1
                    || !available.keySet().containsAll(hypothesis.supportingEvidenceIds())
                    || !available.keySet().containsAll(hypothesis.conflictingEvidenceIds())) {
                throw new IllegalArgumentException("RCA_HYPOTHESIS_INVALID");
            }
        }
        if (rca.evidenceAssessment().coverage() < 0 || rca.evidenceAssessment().coverage() > 1) {
            throw new IllegalArgumentException("RCA_EVIDENCE_COVERAGE_INVALID");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RCA_SERIALIZATION_FAILED", exception);
        }
    }

    private static String renderMarkdown(StructuredRca rca, String objectDigest) {
        StringBuilder result = new StringBuilder("# RCA\n\n")
                .append("- Schema: ").append(rca.schemaVersion()).append('\n')
                .append("- Run: ").append(rca.runId()).append('\n')
                .append("- Outcome: ").append(rca.outcome()).append('\n')
                .append("- Source-Digest: ").append(objectDigest).append("\n\n")
                .append("## Summary\n\n").append(rca.summary()).append("\n\n")
                .append("## Root Cause\n\n")
                .append(rca.rootCause() == null ? "未形成唯一根因" : rca.rootCause().rootCauseCode())
                .append("\n\n## Hypotheses\n\n");
        for (HypothesisResult hypothesis : rca.hypotheses()) {
            result.append("- ").append(hypothesis.status()).append(": ").append(hypothesis.title()).append('\n');
        }
        result.append("\n## Citations\n\n");
        for (Citation citation : rca.citations()) {
            result.append("- ").append(citation.evidenceCode()).append(" (`")
                    .append(citation.evidenceId()).append("`)\n");
        }
        result.append("\n## Evidence Assessment\n\n")
                .append("- Coverage: ").append(rca.evidenceAssessment().coverage()).append('\n');
        for (String missing : rca.evidenceAssessment().missingEvidenceCodes()) {
            result.append("- Missing: ").append(missing).append('\n');
        }
        result.append("\n## Actions\n\n");
        for (String section : List.of("immediate", "longTerm", "monitoring", "tests", "humanNextSteps", "rollback")) {
            result.append("### ").append(section).append('\n');
            for (ActionItem action : rca.actions().get(section)) {
                result.append("- ").append(action.actionCode()).append(": ").append(action.description()).append('\n');
            }
        }
        result.append("\n## Limitations\n\n");
        for (String limitation : rca.limitations()) result.append("- ").append(limitation).append('\n');
        return result.toString();
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
        /** Opens and closes one short REPEATABLE READ, READ ONLY transaction. */
        SealedAnalysis inReadOnlyTransaction(RunId runId, long runVersion);
    }

    @FunctionalInterface
    public interface ReportModel {
        StructuredRca generate(SealedAnalysis input);
    }

    @FunctionalInterface
    public interface RcaMetadataWriter {
        void save(RcaMetadata metadata, String jsonArtifact, String markdownArtifact);
    }

    public record SealedAnalysis(
            IncidentId incidentId, RunId runId, long runVersion, String runStatus,
            Instant analysisSealedAt, Instant incidentStartedAt, Instant incidentEndedAt,
            List<EvidenceView> evidence, List<HypothesisView> hypotheses,
            List<RelationView> relations, List<VerificationView> verifications,
            List<String> missingEvidence) {
        public SealedAnalysis {
            Objects.requireNonNull(incidentId, "incidentId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(runStatus, "runStatus");
            Objects.requireNonNull(incidentStartedAt, "incidentStartedAt");
            incidentEndedAt = incidentEndedAt == null ? analysisSealedAt : incidentEndedAt;
            evidence = List.copyOf(evidence);
            hypotheses = List.copyOf(hypotheses);
            relations = List.copyOf(relations);
            verifications = List.copyOf(verifications);
            missingEvidence = List.copyOf(missingEvidence);
        }
    }

    public record EvidenceView(
            EvidenceId evidenceId, String evidenceCode, String summary,
            ArtifactId artifactId, String artifactSha256, boolean callerCanAccess,
            Instant observedAt) { }
    public record HypothesisView(HypothesisId hypothesisId, String statement, String status, double confidence) { }
    public record RelationView(HypothesisId hypothesisId, EvidenceId evidenceId, String relation) { }
    public record VerificationView(HypothesisId hypothesisId, EvidenceId evidenceId, String result, String summary) { }

    public record RootCause(
            String rootCauseCode, String title, String component, double confidence,
            List<String> supportingEvidenceIds, List<String> conflictingEvidenceIds) {
        public RootCause {
            supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
            conflictingEvidenceIds = List.copyOf(conflictingEvidenceIds);
        }
    }
    public record EvidenceAssessment(
            double coverage, List<String> missingEvidenceCodes, List<String> unavailableCapabilities) {
        public EvidenceAssessment {
            missingEvidenceCodes = List.copyOf(missingEvidenceCodes);
            unavailableCapabilities = List.copyOf(unavailableCapabilities);
        }
    }
    public record HypothesisResult(
            String hypothesisId, String title, String status, double confidence,
            List<String> supportingEvidenceIds, List<String> conflictingEvidenceIds) {
        public HypothesisResult {
            supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
            conflictingEvidenceIds = List.copyOf(conflictingEvidenceIds);
        }
    }
    public record ActionItem(String actionCode, String description, boolean approvalRequired) { }
    public record Citation(String claimId, String evidenceId, String evidenceCode, String artifactId) { }
    public record StructuredRca(
            String schemaVersion, String incidentId, String runId, String summary,
            String severity, String outcome, RootCause rootCause,
            EvidenceAssessment evidenceAssessment, List<HypothesisResult> hypotheses,
            Map<String, List<ActionItem>> actions, List<Citation> citations,
            List<String> limitations, Instant generatedAt) {
        public StructuredRca {
            hypotheses = List.copyOf(hypotheses);
            Map<String, List<ActionItem>> copy = new LinkedHashMap<>();
            actions.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            actions = Map.copyOf(copy);
            citations = List.copyOf(citations);
            limitations = List.copyOf(limitations);
        }
    }
    public record RcaMetadata(
            RunId runId, long runVersion, Instant analysisSealedAt,
            String schemaVersion, String inputDigest, String objectDigest, String markdownDigest) { }
    public record RenderedReport(
            String inputDigest, String objectDigest, StructuredRca rca,
            String jsonArtifact, String markdownArtifact) { }
}
