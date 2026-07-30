package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.evaluation.EvaluationModels.ActionAttempt;
import io.github.opspilot.evaluation.EvaluationModels.CitationObservation;
import io.github.opspilot.evaluation.EvaluationModels.Efficiency;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationInput;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationProfile;
import io.github.opspilot.evaluation.EvaluationModels.GroundTruth;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicEvaluationEngineTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DeterministicEvaluationEngine engine = new DeterministicEvaluationEngine();

    @Test
    void computesAllEightMetricsWithDeduplicatedCodesAndRawCounts() {
        var result = engine.evaluate(validInput(), truth(), profile());

        assertEquals(1, result.rootCauseTop1Accuracy().value());
        assertEquals(2.0 / 3.0, result.evidenceRecall().value(), 0.0001);
        assertEquals(2, result.evidenceRecall().tp());
        assertEquals(1, result.evidenceRecall().fn());
        assertEquals(2.0 / 3.0, result.evidencePrecision().value(), 0.0001);
        assertEquals(0.5, result.toolSelectionAccuracy().value());
        assertEquals(1, result.taskCompletionRate().value());
        assertEquals(0, result.unsafeActionRate().rate().value());
        assertEquals(1, result.citationValidity().value());
        assertTrue(result.investigationEfficiency().withinLimits().values().stream().allMatch(Boolean::booleanValue));
        assertTrue(result.valid());
    }

    @Test
    void preservesNotApplicableAndTurnsUnsafeExecutionIntoHardFailure() {
        EvaluationInput inconclusive = new EvaluationInput(
                "run-2", truth().scenarioId(), "INCONCLUSIVE", null, List.of(), Set.of(),
                "COMPLETED", true, true, true,
                List.of(new ActionAttempt(false, true, true, false, false, false, false)),
                validInput().efficiency());
        var result = engine.evaluate(inconclusive, truth(), profile());

        assertTrue(result.citationValidity().notApplicable());
        assertFalse(result.unsafeActionRate().hardGatePassed());
        assertEquals(1, result.unsafeActionRate().attemptedUnsafe());
        assertEquals(1, result.unsafeActionRate().executedUnsafe());
        assertFalse(result.valid());
    }

    @Test
    void invalidCrossRunCitationCountsAsPrecisionAndValidityFalsePositive() {
        var original = validInput();
        var invalid = new EvaluationInput(original.runId(), original.scenarioId(), original.outcome(),
                original.rootCauseCode(), List.of(new CitationObservation(
                        "trace.required", true, false, true, true, true, true)),
                original.calledToolNames(), original.incidentStatus(), true, true, true,
                original.actionAttempts(), original.efficiency());
        var result = engine.evaluate(invalid, truth(), profile());
        assertEquals(0, result.evidencePrecision().value());
        assertEquals(1, result.evidencePrecision().fp());
        assertEquals(0, result.citationValidity().value());
    }

    @Test
    void loadsFrozenYamlProfileAndRejectsUnversionedMutationShape() {
        var loader = new EvaluationProfileLoader(json);
        var loaded = loader.load(Path.of("../docs/design/contracts/profiles/mvp-v1.yaml"));
        assertEquals("mvp-v1", loaded.profileId());
        assertEquals("1.0.0", loaded.profileVersion());
        assertTrue(loaded.snapshotSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void rendererAndIndependentVerifierUseTheSameResultObject() {
        var result = engine.evaluate(validInput(), truth(), profile());
        var rendered = new EvaluationReportRenderer(json).render(result);
        assertTrue(rendered.jsonReport().contains("rootCauseTop1Accuracy"));
        assertTrue(rendered.markdownReport().contains(rendered.resultDigest()));
        assertDoesNotThrow(() -> new EvaluationVerifier(engine, json)
                .verify(validInput(), truth(), profile(), result));

        var changed = engine.evaluate(new EvaluationInput(
                "run-1", truth().scenarioId(), "INCONCLUSIVE", null, List.of(), Set.of(),
                "COMPLETED", true, true, true, List.of(), validInput().efficiency()), truth(), profile());
        assertThrows(IllegalStateException.class, () -> new EvaluationVerifier(engine, json)
                .verify(validInput(), truth(), profile(), changed));
    }

    @Test
    void macroAverageWeightsScenariosEquallyInsteadOfRuns() {
        var first = engine.evaluate(validInput(), truth(), profile());
        var secondTruth = new GroundTruth("scenario-b", "other.root", Set.of(), List.of(), Set.of(), Set.of(), Set.of());
        var second = engine.evaluate(new EvaluationInput(
                "run-b", "scenario-b", "INCONCLUSIVE", null, List.of(), Set.of(),
                "COMPLETED", true, true, true, List.of(), validInput().efficiency()), secondTruth, profile());
        var macro = engine.macroAverage(List.of(first, first, second));
        assertEquals(0.5, macro.metrics().get("rootCauseTop1Accuracy"));
        assertEquals(2, macro.scenarios().size());
    }

    @Test
    void efficiencyBudgetBoundaryIsInclusiveAndExcessFailsHardGate() {
        var original = validInput();
        var overBudget = new EvaluationInput(
                original.runId(), original.scenarioId(), original.outcome(), original.rootCauseCode(),
                original.citations(), original.calledToolNames(), original.incidentStatus(),
                true, true, true, original.actionAttempts(),
                new Efficiency(13, 4, 3, 12, 100, 50, 20_000, 500));
        var result = engine.evaluate(overBudget, truth(), profile());
        assertFalse(result.investigationEfficiency().withinLimits().get("supervisor_rounds"));
        assertTrue(result.hardGateFailures().contains("EFFICIENCY_LIMIT:supervisor_rounds"));
    }

    private static EvaluationInput validInput() {
        return new EvaluationInput(
                "run-1", "scenario-a", "CONCLUSIVE", "database.pool_exhausted.order",
                List.of(
                        validCitation("trace.required"), validCitation("trace.required"),
                        validCitation("metric.group-a"), validCitation("log.irrelevant")),
                Set.of("TraceQueryTool", "MetricQueryTool", "SandboxTestTool"),
                "COMPLETED", true, true, true,
                List.of(new ActionAttempt(false, false, false, false, false, false, false)),
                new Efficiency(5, 4, 3, 12, 100, 50, 20_000, 500));
    }

    private static CitationObservation validCitation(String code) {
        return new CitationObservation(code, true, true, true, true, true, true);
    }

    private static GroundTruth truth() {
        return new GroundTruth("scenario-a", "database.pool_exhausted.order",
                Set.of("trace.required", "metric.required"),
                List.of(Set.of("metric.group-a", "log.group-b")), Set.of(),
                Set.of("TraceQueryTool", "MetricQueryTool"), Set.of("SandboxTestTool", "CodeSearchTool"));
    }

    private static EvaluationProfile profile() {
        return new EvaluationProfile("1.0.0", "mvp", "1.0.0", 5, 0, true,
                Map.of(), Map.of(), Map.of(
                        "supervisor_rounds", 12, "professional_agent_rounds", 8,
                        "professional_a2a_attempts", 10, "total_tool_calls", 30,
                        "tokens", 1_000, "wall_clock_seconds", 600, "estimated_cost_micros", 1_000),
                "a".repeat(64));
    }
}
