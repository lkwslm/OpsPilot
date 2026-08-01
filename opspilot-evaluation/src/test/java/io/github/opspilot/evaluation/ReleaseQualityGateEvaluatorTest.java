package io.github.opspilot.evaluation;

import io.github.opspilot.evaluation.EvaluationModels.Efficiency;
import io.github.opspilot.evaluation.EvaluationModels.EfficiencyMetric;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationResult;
import io.github.opspilot.evaluation.EvaluationModels.Ratio;
import io.github.opspilot.evaluation.EvaluationModels.UnsafeActionMetric;
import io.github.opspilot.evaluation.ReleaseQualityAggregator.ReleaseEvaluationRun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseQualityGateEvaluatorTest {
    private static final List<String> SCENARIOS = List.of(
            "database-pool-exhausted-order",
            "dependency-latency-inventory",
            "service-instance-stopped-inventory");

    @Test
    void passesInclusiveBoundariesAndPreservesCitationNotApplicable() {
        List<ReleaseEvaluationRun> runs = batch(new int[]{3, 4, 5}, new int[]{2, 1, 0}, false);
        var aggregate = new ReleaseQualityAggregator().aggregate(runs);

        var gate = new ReleaseQualityGateEvaluator().evaluate(aggregate, 15, 15);

        assertEquals("PASSED", gate.status());
        assertEquals(1, gate.unsafeAttempted());
        assertEquals(0, gate.unsafeExecuted());
        assertEquals(14, aggregate.macroAverage().get("citationValidity").denominator());
        assertEquals(0.8, aggregate.macroAverage().get("rootCauseTop1Accuracy").value(), 0.0001);
        assertEquals(0.2, aggregate.macroAverage().get("inconclusiveRate").value(), 0.0001);
    }

    @Test
    void failsWhenOneScenarioMissesRootCauseFloorEvenIfMacroPasses() {
        List<ReleaseEvaluationRun> runs = batch(new int[]{2, 5, 5}, new int[]{1, 1, 1}, false);
        var gate = new ReleaseQualityGateEvaluator().evaluate(
                new ReleaseQualityAggregator().aggregate(runs), 15, 15);

        assertEquals("FAILED", gate.status());
        assertEquals(0.8, gate.checks().stream()
                .filter(check -> check.scope().equals("OVERALL")
                        && check.metric().equals("rootCauseTop1Accuracy"))
                .findFirst().orElseThrow().actual(), 0.0001);
        assertEquals(false, gate.checks().stream()
                .filter(check -> check.scope().equals(SCENARIOS.getFirst())
                        && check.metric().equals("rootCauseHits"))
                .findFirst().orElseThrow().passed());
    }

    @Test
    void failsForUnsafeExecutionOrInvalidDataset() {
        List<ReleaseEvaluationRun> runs = batch(new int[]{5, 5, 5}, new int[]{0, 0, 0}, true);
        var aggregate = new ReleaseQualityAggregator().aggregate(runs);

        var unsafe = new ReleaseQualityGateEvaluator().evaluate(aggregate, 15, 15);
        var dataset = new ReleaseQualityGateEvaluator().evaluate(aggregate, 14, 15);

        assertEquals("FAILED", unsafe.status());
        assertEquals(1, unsafe.unsafeExecuted());
        assertEquals("FAILED", dataset.status());
    }

    private static List<ReleaseEvaluationRun> batch(
            int[] rootCauseHits, int[] inconclusiveCounts, boolean executeUnsafe) {
        List<ReleaseEvaluationRun> runs = new ArrayList<>();
        int number = 0;
        for (int scenarioIndex = 0; scenarioIndex < SCENARIOS.size(); scenarioIndex++) {
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                number++;
                boolean inconclusive = ordinal <= inconclusiveCounts[scenarioIndex];
                runs.add(run("run-" + number, SCENARIOS.get(scenarioIndex),
                        ordinal <= rootCauseHits[scenarioIndex],
                        inconclusive ? "INCONCLUSIVE" : "CONCLUSIVE",
                        number == 1, executeUnsafe && number == 1, inconclusive && number == 1));
            }
        }
        return runs;
    }

    private static ReleaseEvaluationRun run(
            String runId, String scenario, boolean rootCauseHit, String outcome,
            boolean unsafeAttempted, boolean unsafeExecuted, boolean citationNotApplicable) {
        Ratio rootCause = Ratio.of(rootCauseHit ? 1 : 0, 1, 0, 0, 0);
        Ratio passed = Ratio.of(1, 1, 0, 0, 0);
        Ratio citation = citationNotApplicable ? Ratio.notApplicableValue() : passed;
        UnsafeActionMetric unsafe = new UnsafeActionMetric(
                Ratio.of(unsafeAttempted ? 1 : 0, 1, 0, 0, 0),
                unsafeAttempted ? 1 : 0, unsafeExecuted ? 1 : 0, !unsafeExecuted);
        EvaluationResult result = new EvaluationResult(
                "1.0.0", runId, scenario, rootCause, passed, passed, passed, passed,
                unsafe, new EfficiencyMetric(
                        new Efficiency(1, 1, 1, 1, 1, 1, 1, 1), Map.of()),
                citation, !unsafeExecuted, unsafeExecuted ? List.of("UNSAFE") : List.of());
        return new ReleaseEvaluationRun(outcome, result);
    }
}
