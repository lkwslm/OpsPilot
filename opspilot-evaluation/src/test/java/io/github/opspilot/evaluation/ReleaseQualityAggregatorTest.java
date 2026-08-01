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
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseQualityAggregatorTest {
    private static final List<String> SCENARIOS = List.of(
            "database-pool-exhausted-order",
            "dependency-latency-inventory",
            "service-instance-stopped-inventory");

    @Test
    void preservesPerRunScenarioAndMacroCounts() {
        List<ReleaseEvaluationRun> runs = new ArrayList<>();
        int number = 0;
        for (String scenario : SCENARIOS) {
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                number++;
                runs.add(run("run-" + number, scenario, ordinal <= 4,
                        ordinal == 5 ? "INCONCLUSIVE" : "CONCLUSIVE"));
            }
        }

        var aggregate = new ReleaseQualityAggregator().aggregate(runs);

        assertEquals(3, aggregate.scenarios().size());
        assertEquals(4, aggregate.scenarios().getFirst().rootCauseHits());
        assertEquals(5, aggregate.scenarios().getFirst().metrics()
                .get("rootCauseTop1Accuracy").runs().size());
        assertEquals(4, aggregate.scenarios().getFirst().metrics()
                .get("rootCauseTop1Accuracy").numerator());
        assertEquals(5, aggregate.scenarios().getFirst().metrics()
                .get("rootCauseTop1Accuracy").denominator());
        assertEquals(0.8, aggregate.macroAverage().get("rootCauseTop1Accuracy").value(), 0.0001);
        assertEquals(0.2, aggregate.macroAverage().get("inconclusiveRate").value(), 0.0001);
        assertEquals(12, aggregate.macroAverage().get("rootCauseTop1Accuracy").numerator());
        assertEquals(15, aggregate.macroAverage().get("rootCauseTop1Accuracy").denominator());
    }

    @Test
    void rejectsMissingRunInsteadOfChangingTheDenominator() {
        List<ReleaseEvaluationRun> incomplete = new ArrayList<>();
        for (String scenario : SCENARIOS) {
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                incomplete.add(run(scenario + ordinal, scenario, true, "CONCLUSIVE"));
            }
        }
        incomplete.removeLast();

        assertThrows(IllegalArgumentException.class,
                () -> new ReleaseQualityAggregator().aggregate(incomplete));
    }

    private static ReleaseEvaluationRun run(
            String runId, String scenario, boolean rootCauseHit, String outcome) {
        Ratio rootCause = Ratio.of(rootCauseHit ? 1 : 0, 1, 0, 0, 0);
        Ratio passed = Ratio.of(1, 1, 0, 0, 0);
        EvaluationResult result = new EvaluationResult(
                "1.0.0", runId, scenario, rootCause, passed, passed, passed, passed,
                new UnsafeActionMetric(Ratio.of(0, 1, 0, 0, 0), 0, 0, true),
                new EfficiencyMetric(new Efficiency(1, 1, 1, 1, 1, 1, 1, 1), Map.of()),
                passed, true, List.of());
        return new ReleaseEvaluationRun(outcome, result);
    }
}
