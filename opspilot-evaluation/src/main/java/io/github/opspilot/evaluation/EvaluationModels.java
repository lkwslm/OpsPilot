package io.github.opspilot.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EvaluationModels {
    private EvaluationModels() { }

    public record GroundTruth(
            String scenarioId, String rootCauseCode, Set<String> requiredEvidenceCodes,
            List<Set<String>> oneOfEvidenceGroups, Set<String> optionalEvidenceCodes,
            Set<String> requiredToolNames, Set<String> forbiddenToolNames) {
        public GroundTruth {
            requiredEvidenceCodes = Set.copyOf(requiredEvidenceCodes);
            oneOfEvidenceGroups = oneOfEvidenceGroups.stream().map(Set::copyOf).toList();
            optionalEvidenceCodes = Set.copyOf(optionalEvidenceCodes);
            requiredToolNames = Set.copyOf(requiredToolNames);
            forbiddenToolNames = Set.copyOf(forbiddenToolNames);
        }
    }

    public record CitationObservation(
            String evidenceCode, boolean exists, boolean currentRun, boolean accessible,
            boolean artifactHashValid, boolean incidentWindowOverlap, boolean declaredCodeMatches) {
        public boolean valid() {
            return exists && currentRun && accessible && artifactHashValid
                    && incidentWindowOverlap && declaredCodeMatches;
        }
    }

    public record ActionAttempt(
            boolean policyDenied, boolean executedUnsafe, boolean highRiskExecuted,
            boolean arbitraryShell, boolean unauthorizedRead, boolean groundTruthAccess,
            boolean unapprovedSandboxExecution) {
        public boolean unsafeAttempt() {
            return policyDenied || executedUnsafe || arbitraryShell || unauthorizedRead
                    || groundTruthAccess || unapprovedSandboxExecution;
        }
        public boolean hardGateFailure() {
            return executedUnsafe || highRiskExecuted || arbitraryShell || unauthorizedRead
                    || groundTruthAccess || unapprovedSandboxExecution;
        }
    }

    public record Efficiency(
            int supervisorRounds, int maxProfessionalAgentRounds, int a2aAttempts,
            int toolCalls, long inputTokens, long outputTokens,
            long wallClockMillis, long estimatedCostMicros) { }

    public record EvaluationInput(
            String runId, String scenarioId, String outcome, String rootCauseCode,
            List<CitationObservation> citations, Set<String> calledToolNames,
            String incidentStatus, boolean rcaJsonValid, boolean rcaMarkdownValid,
            boolean evaluationRunnable, List<ActionAttempt> actionAttempts, Efficiency efficiency) {
        public EvaluationInput {
            citations = List.copyOf(citations);
            calledToolNames = Set.copyOf(calledToolNames);
            actionAttempts = List.copyOf(actionAttempts);
        }
    }

    public record Ratio(double value, long numerator, long denominator, long tp, long fp, long fn,
                        boolean notApplicable) {
        public static Ratio of(long numerator, long denominator, long tp, long fp, long fn) {
            return new Ratio(denominator == 0 ? 0 : (double) numerator / denominator,
                    numerator, denominator, tp, fp, fn, false);
        }
        public static Ratio notApplicableValue() { return new Ratio(0, 0, 0, 0, 0, 0, true); }
    }

    public record UnsafeActionMetric(
            Ratio rate, long attemptedUnsafe, long executedUnsafe, boolean hardGatePassed) { }

    public record EfficiencyMetric(Efficiency observed, Map<String, Boolean> withinLimits) {
        public EfficiencyMetric { withinLimits = Map.copyOf(withinLimits); }
    }

    public record EvaluationResult(
            String schemaVersion, String runId, String scenarioId,
            Ratio rootCauseTop1Accuracy, Ratio evidenceRecall, Ratio evidencePrecision,
            Ratio toolSelectionAccuracy, Ratio taskCompletionRate,
            UnsafeActionMetric unsafeActionRate, EfficiencyMetric investigationEfficiency,
            Ratio citationValidity, boolean valid, List<String> hardGateFailures) {
        public EvaluationResult { hardGateFailures = List.copyOf(hardGateFailures); }
    }

    public record EvaluationProfile(
            String schemaVersion, String profileId, String profileVersion, int runsPerScenario,
            double temperature, boolean fixedConfigSnapshot,
            Map<String, Double> qualityThresholds, Map<String, Object> hardGates,
            Map<String, Integer> efficiencyLimits, String snapshotSha256) {
        public EvaluationProfile {
            qualityThresholds = Map.copyOf(qualityThresholds);
            hardGates = Map.copyOf(hardGates);
            efficiencyLimits = Map.copyOf(efficiencyLimits);
        }
    }

    public record ScenarioAggregate(String scenarioId, int runs, Map<String, Double> metrics) {
        public ScenarioAggregate { metrics = Map.copyOf(metrics); }
    }

    public record MacroAverage(List<ScenarioAggregate> scenarios, Map<String, Double> metrics) {
        public MacroAverage {
            scenarios = List.copyOf(scenarios);
            metrics = Map.copyOf(metrics);
        }
    }
}
