package io.github.opspilot.evaluation;

import io.github.opspilot.evaluation.EvaluationModels.EvaluationResult;
import io.github.opspilot.evaluation.EvaluationModels.Ratio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Aggregates a fixed 3x5 quality batch without changing denominators. */
public final class ReleaseQualityAggregator {
    private static final Set<String> SCENARIOS = Set.of(
            "database-pool-exhausted-order",
            "dependency-latency-inventory",
            "service-instance-stopped-inventory");
    private static final Map<String, Function<EvaluationResult, Ratio>> RATIO_METRICS = Map.of(
            "rootCauseTop1Accuracy", EvaluationResult::rootCauseTop1Accuracy,
            "evidenceRecall", EvaluationResult::evidenceRecall,
            "evidencePrecision", EvaluationResult::evidencePrecision,
            "toolSelectionAccuracy", EvaluationResult::toolSelectionAccuracy,
            "taskCompletionRate", EvaluationResult::taskCompletionRate,
            "citationValidity", EvaluationResult::citationValidity);

    public QualityAggregate aggregate(List<ReleaseEvaluationRun> runs) {
        if (runs.size() != 15) invalid("run count");
        Map<String, List<ReleaseEvaluationRun>> byScenario = new LinkedHashMap<>();
        Set<String> runIds = new HashSet<>();
        for (ReleaseEvaluationRun run : runs) {
            EvaluationResult result = run.result();
            if (!SCENARIOS.contains(result.scenarioId()) || !runIds.add(result.runId())) {
                invalid("scenario or duplicate run");
            }
            byScenario.computeIfAbsent(result.scenarioId(), ignored -> new ArrayList<>()).add(run);
        }
        if (!byScenario.keySet().equals(SCENARIOS)
                || byScenario.values().stream().anyMatch(values -> values.size() != 5)) {
            invalid("3x5 scenario coverage");
        }

        List<ScenarioQuality> scenarios = SCENARIOS.stream().sorted()
                .map(scenario -> scenario(scenario, byScenario.get(scenario))).toList();
        Map<String, MacroMetric> macro = new LinkedHashMap<>();
        for (String metric : scenarios.getFirst().metrics().keySet()) {
            List<MetricDetail> values = scenarios.stream().map(value -> value.metrics().get(metric)).toList();
            boolean notApplicable = values.stream().anyMatch(MetricDetail::notApplicable);
            macro.put(metric, new MacroMetric(
                    notApplicable ? 0 : values.stream().mapToDouble(MetricDetail::value).average().orElseThrow(),
                    values.stream().mapToLong(MetricDetail::numerator).sum(),
                    values.stream().mapToLong(MetricDetail::denominator).sum(),
                    notApplicable));
        }
        return new QualityAggregate(scenarios, macro);
    }

    private ScenarioQuality scenario(String scenarioId, List<ReleaseEvaluationRun> runs) {
        Map<String, MetricDetail> metrics = new LinkedHashMap<>();
        RATIO_METRICS.forEach((name, extractor) -> metrics.put(name,
                detail(runs, run -> extractor.apply(run.result()))));
        metrics.put("unsafeActionExecutedRate", detail(runs, run -> Ratio.of(
                run.result().unsafeActionRate().executedUnsafe(),
                run.result().unsafeActionRate().rate().denominator(),
                run.result().unsafeActionRate().executedUnsafe(), 0, 0)));
        metrics.put("unsafeActionAttemptedRate", detail(runs,
                run -> run.result().unsafeActionRate().rate()));
        metrics.put("inconclusiveRate", detail(runs, run -> Ratio.of(
                "INCONCLUSIVE".equals(run.outcome()) ? 1 : 0, 1, 0, 0, 0)));
        long rootCauseHits = runs.stream()
                .map(ReleaseEvaluationRun::result)
                .map(EvaluationResult::rootCauseTop1Accuracy)
                .mapToLong(Ratio::numerator).sum();
        return new ScenarioQuality(scenarioId, runs.size(), rootCauseHits, metrics);
    }

    private static MetricDetail detail(
            List<ReleaseEvaluationRun> runs,
            Function<ReleaseEvaluationRun, Ratio> extractor) {
        List<RunMetric> perRun = runs.stream().map(run -> {
            Ratio ratio = extractor.apply(run);
            return new RunMetric(run.result().runId(), ratio.value(), ratio.numerator(),
                    ratio.denominator(), ratio.notApplicable());
        }).toList();
        long numerator = perRun.stream().mapToLong(RunMetric::numerator).sum();
        long denominator = perRun.stream().mapToLong(RunMetric::denominator).sum();
        boolean notApplicable = perRun.stream().allMatch(RunMetric::notApplicable);
        return new MetricDetail(notApplicable || denominator == 0 ? 0 : (double) numerator / denominator,
                numerator, denominator, notApplicable, perRun);
    }

    private static void invalid(String detail) {
        throw new IllegalArgumentException("RELEASE_QUALITY_AGGREGATE_INVALID: " + detail);
    }

    public record ReleaseEvaluationRun(String outcome, EvaluationResult result) {
        public ReleaseEvaluationRun {
            if (!Set.of("CONCLUSIVE", "PARTIAL", "INCONCLUSIVE").contains(outcome) || result == null) {
                invalid("run outcome");
            }
        }
    }

    public record RunMetric(
            String runId, double value, long numerator, long denominator, boolean notApplicable) { }

    public record MetricDetail(
            double value, long numerator, long denominator, boolean notApplicable,
            List<RunMetric> runs) {
        public MetricDetail { runs = List.copyOf(runs); }
    }

    public record ScenarioQuality(
            String scenarioId, int runs, long rootCauseHits, Map<String, MetricDetail> metrics) {
        public ScenarioQuality { metrics = Map.copyOf(metrics); }
    }

    public record MacroMetric(double value, long numerator, long denominator, boolean notApplicable) { }

    public record QualityAggregate(
            List<ScenarioQuality> scenarios, Map<String, MacroMetric> macroAverage) {
        public QualityAggregate {
            scenarios = List.copyOf(scenarios);
            macroAverage = Map.copyOf(macroAverage);
        }
    }
}
