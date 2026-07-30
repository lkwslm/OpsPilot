package io.github.opspilot.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Renders both formats only from one immutable EvaluationResult. */
public final class EvaluationReportRenderer {
    private final ObjectMapper json;
    public EvaluationReportRenderer(ObjectMapper json) { this.json = json; }

    public RenderedEvaluation render(EvaluationResult result) {
        try {
            String jsonReport = json.writeValueAsString(result);
            String digest = sha256(jsonReport);
            String markdown = """
                    # Evaluation

                    - Schema: %s
                    - Run: %s
                    - Scenario: %s
                    - Source-Digest: %s

                    | Metric | Value | Numerator | Denominator |
                    |---|---:|---:|---:|
                    | RootCauseTop1Accuracy | %s | %s | %s |
                    | EvidenceRecall | %s | %s | %s |
                    | EvidencePrecision | %s | %s | %s |
                    | ToolSelectionAccuracy | %s | %s | %s |
                    | TaskCompletionRate | %s | %s | %s |
                    | UnsafeActionRate | %s | %s | %s |
                    | CitationValidity | %s | %s | %s |

                    Hard gate passed: %s
                    Failures: %s
                    Efficiency: %s
                    """.formatted(result.schemaVersion(), result.runId(), result.scenarioId(), digest,
                    result.rootCauseTop1Accuracy().value(), result.rootCauseTop1Accuracy().numerator(), result.rootCauseTop1Accuracy().denominator(),
                    result.evidenceRecall().value(), result.evidenceRecall().numerator(), result.evidenceRecall().denominator(),
                    result.evidencePrecision().value(), result.evidencePrecision().numerator(), result.evidencePrecision().denominator(),
                    result.toolSelectionAccuracy().value(), result.toolSelectionAccuracy().numerator(), result.toolSelectionAccuracy().denominator(),
                    result.taskCompletionRate().value(), result.taskCompletionRate().numerator(), result.taskCompletionRate().denominator(),
                    result.unsafeActionRate().rate().value(), result.unsafeActionRate().rate().numerator(), result.unsafeActionRate().rate().denominator(),
                    result.citationValidity().value(), result.citationValidity().numerator(), result.citationValidity().denominator(),
                    result.valid(), result.hardGateFailures(), result.investigationEfficiency().observed());
            return new RenderedEvaluation(result, digest, jsonReport, markdown);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("EVALUATION_RENDER_FAILED", exception);
        }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record RenderedEvaluation(
            EvaluationResult result, String resultDigest, String jsonReport, String markdownReport) { }
}
