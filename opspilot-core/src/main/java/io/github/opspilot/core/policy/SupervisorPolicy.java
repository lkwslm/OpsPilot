package io.github.opspilot.core.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** One-step bounded policy evaluated by the Agent runtime adapter; this is not an Agent loop. */
public final class SupervisorPolicy {
    public Decision evaluate(Evaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (evaluation.cancellationRequested()) {
            return Decision.stop(StopReason.CANCELLED);
        }
        if (evaluation.criticalFailure()) {
            return Decision.stop(StopReason.CRITICAL_FAILURE);
        }
        if (!evaluation.now().isBefore(evaluation.limits().deadline())) {
            return Decision.limitedReport(StopReason.DEADLINE_EXCEEDED);
        }
        if (!withinBudget(evaluation.limits(), evaluation.usage())) {
            return Decision.limitedReport(StopReason.BUDGET_EXHAUSTED);
        }
        if (evaluation.progress().consecutiveNoProgress() >= evaluation.limits().maxNoProgress()) {
            return Decision.limitedReport(StopReason.NO_PROGRESS);
        }
        if (evaluation.inputRequired()) {
            return new Decision(Action.WAIT_FOR_INPUT, StopReason.INPUT_REQUIRED);
        }
        return new Decision(Action.CONTINUE, null);
    }

    public FrozenLimits applyModelRequest(FrozenLimits frozen, ModelLimitRequest ignored) {
        Objects.requireNonNull(ignored, "model limit request");
        return Objects.requireNonNull(frozen, "frozen limits");
    }

    private static boolean withinBudget(FrozenLimits limit, Usage usage) {
        return usage.rounds() < limit.maxRounds()
                && usage.agentCalls() < limit.maxAgentCalls()
                && usage.toolCalls() < limit.maxToolCalls()
                && usage.a2aCalls() < limit.maxA2aCalls()
                && usage.tokens() < limit.maxTokens()
                && usage.costMicros() < limit.maxCostMicros();
    }

    public record FrozenLimits(
            int maxRounds,
            int maxAgentCalls,
            int maxToolCalls,
            int maxA2aCalls,
            long maxTokens,
            long maxCostMicros,
            int maxNoProgress,
            int maxPlanSteps,
            Instant deadline) {
        public FrozenLimits {
            if (maxRounds < 1 || maxAgentCalls < 1 || maxToolCalls < 1 || maxA2aCalls < 1
                    || maxTokens < 1 || maxCostMicros < 1 || maxNoProgress < 1 || maxPlanSteps < 1) {
                throw new IllegalArgumentException("all supervisor limits must be positive");
            }
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record Usage(int rounds, int agentCalls, int toolCalls, int a2aCalls, long tokens, long costMicros) {
    }

    public record ModelLimitRequest(int maxRounds, long maxCostMicros) {
    }

    public record Progress(
            Set<String> actionFingerprints,
            Set<String> evidenceFingerprints,
            int consecutiveNoProgress) {
        public Progress {
            actionFingerprints = Set.copyOf(actionFingerprints);
            evidenceFingerprints = Set.copyOf(evidenceFingerprints);
            if (consecutiveNoProgress < 0) {
                throw new IllegalArgumentException("consecutiveNoProgress must be non-negative");
            }
        }

        public Progress observe(String actionFingerprint, Set<String> newEvidenceFingerprints) {
            boolean novelAction = !actionFingerprints.contains(actionFingerprint);
            boolean novelEvidence = newEvidenceFingerprints.stream().anyMatch(value -> !evidenceFingerprints.contains(value));
            Set<String> actions = new java.util.HashSet<>(actionFingerprints);
            actions.add(actionFingerprint);
            Set<String> evidence = new java.util.HashSet<>(evidenceFingerprints);
            evidence.addAll(newEvidenceFingerprints);
            return new Progress(actions, evidence, novelAction || novelEvidence ? 0 : consecutiveNoProgress + 1);
        }
    }

    public record Evaluation(
            FrozenLimits limits,
            Usage usage,
            Progress progress,
            Instant now,
            boolean inputRequired,
            boolean criticalFailure,
            boolean cancellationRequested) {
    }

    public enum Action { CONTINUE, WAIT_FOR_INPUT, GENERATE_LIMITED_REPORT, STOP }

    public enum StopReason {
        CANCELLED, CRITICAL_FAILURE, DEADLINE_EXCEEDED, BUDGET_EXHAUSTED, NO_PROGRESS, INPUT_REQUIRED
    }

    public record Decision(Action action, StopReason reason) {
        static Decision stop(StopReason reason) { return new Decision(Action.STOP, reason); }
        static Decision limitedReport(StopReason reason) {
            return new Decision(Action.GENERATE_LIMITED_REPORT, reason);
        }
    }
}
