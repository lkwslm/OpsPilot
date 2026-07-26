package io.github.opspilot.core.application.governance;

import io.github.opspilot.core.application.checkpoint.CheckpointService;
import io.github.opspilot.core.application.checkpoint.CheckpointService.CommitResult;
import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.domain.state.IncidentAgentState.TokenBudgetSnapshot;
import io.github.opspilot.core.domain.state.StateMachines;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.DomainEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Applies the frozen token recovery ladder and checkpoints exhaustion before stopping calls. */
public final class BudgetRecoveryCoordinator {
    public static final String TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED";

    public enum RecoveryAction {
        COMPACT_CONTEXT,
        REDUCE_KNOWLEDGE_CANDIDATES,
        SHRINK_CODE_AND_TIME_WINDOW,
        SPLIT_SUBTASK,
        SUPERVISOR_REPLAN
    }

    public record RecoveryRequest(
            long requiredTokens,
            long compactableTokens,
            int knowledgeCandidateCount,
            int minimumKnowledgeCandidateCount,
            long tokensPerKnowledgeCandidate,
            long shrinkableCodeAndTimeWindowTokens,
            long splittableSubtaskTokens,
            long supervisorReplanTokens,
            List<String> missingWork,
            List<String> limitations,
            String nextStep) {
        public RecoveryRequest {
            if (requiredTokens < 0 || compactableTokens < 0 || knowledgeCandidateCount < 0
                    || minimumKnowledgeCandidateCount < 0
                    || minimumKnowledgeCandidateCount > knowledgeCandidateCount
                    || tokensPerKnowledgeCandidate < 0 || shrinkableCodeAndTimeWindowTokens < 0
                    || splittableSubtaskTokens < 0 || supervisorReplanTokens < 0) {
                throw new IllegalArgumentException("recovery values must be non-negative and bounded");
            }
            missingWork = List.copyOf(missingWork);
            limitations = List.copyOf(limitations);
            if (nextStep == null || nextStep.isBlank()) {
                throw new IllegalArgumentException("nextStep must not be blank");
            }
        }
    }

    public record RecoveryResult(
            boolean newModelCallsAllowed,
            String errorCode,
            long requiredTokensAfterRecovery,
            long remainingTokens,
            int retainedKnowledgeCandidates,
            List<RecoveryAction> actions,
            UUID checkpointId,
            IncidentAgentState state) {
        public RecoveryResult {
            actions = List.copyOf(actions);
        }
    }

    private final CheckpointService checkpoints;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public BudgetRecoveryCoordinator(CheckpointService checkpoints, Clock clock, Supplier<UUID> ids) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public RecoveryResult recover(IncidentAgentState current, RecoveryRequest request) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(request, "request");
        long remaining = current.tokenBudget().limit() - current.tokenBudget().consumed();
        long required = request.requiredTokens();
        int retainedCandidates = request.knowledgeCandidateCount();
        List<RecoveryAction> actions = new ArrayList<>();

        required = reclaimIfNeeded(required, remaining, request.compactableTokens(),
                RecoveryAction.COMPACT_CONTEXT, actions);
        if (required > remaining && retainedCandidates > request.minimumKnowledgeCandidateCount()) {
            long shortfall = required - remaining;
            long perCandidate = request.tokensPerKnowledgeCandidate();
            int removable = retainedCandidates - request.minimumKnowledgeCandidateCount();
            int remove = perCandidate == 0 ? 0
                    : (int) Math.min(removable, divideRoundingUp(shortfall, perCandidate));
            if (remove > 0) {
                retainedCandidates -= remove;
                required = Math.max(0, required - Math.multiplyExact(remove, perCandidate));
                actions.add(RecoveryAction.REDUCE_KNOWLEDGE_CANDIDATES);
            }
        }
        required = reclaimIfNeeded(required, remaining, request.shrinkableCodeAndTimeWindowTokens(),
                RecoveryAction.SHRINK_CODE_AND_TIME_WINDOW, actions);
        required = reclaimIfNeeded(required, remaining, request.splittableSubtaskTokens(),
                RecoveryAction.SPLIT_SUBTASK, actions);
        required = reclaimIfNeeded(required, remaining, request.supervisorReplanTokens(),
                RecoveryAction.SUPERVISOR_REPLAN, actions);

        if (required <= remaining) {
            return new RecoveryResult(true, null, required, remaining, retainedCandidates,
                    actions, null, current);
        }
        return checkpointExhaustion(current, request, required, remaining, retainedCandidates, actions);
    }

    public void requireNewModelCallAllowed(IncidentAgentState state) {
        Objects.requireNonNull(state, "state");
        if (state.status() == IncidentRunState.GENERATING_REPORT
                && state.warnings().contains(TOKEN_BUDGET_EXCEEDED)) {
            throw new TokenBudgetExceededException();
        }
    }

    private RecoveryResult checkpointExhaustion(
            IncidentAgentState current,
            RecoveryRequest request,
            long required,
            long remaining,
            int retainedCandidates,
            List<RecoveryAction> actions) {
        UUID checkpointId = ids.get();
        Instant now = clock.instant();
        IncidentAgentState exhausted = exhaustedState(current, request, now);
        CheckpointCommand command = command(checkpointId, exhausted, current.version(), now);
        CommitResult committed = checkpoints.commit(command, (stale, latest) -> {
            if (latest.status() == IncidentRunState.COMPLETED
                    || latest.status() == IncidentRunState.FAILED
                    || latest.status() == IncidentRunState.CANCELLED
                    || latest.status() == IncidentRunState.GENERATING_REPORT) {
                return Optional.empty();
            }
            IncidentAgentState rejudged = exhaustedState(latest, request, now);
            return Optional.of(command(checkpointId, rejudged, latest.version(), now));
        });
        IncidentAgentState saved = committed.state();
        return new RecoveryResult(false, TOKEN_BUDGET_EXCEEDED, required, remaining,
                retainedCandidates, actions, checkpointId, saved);
    }

    private static CheckpointCommand command(
            UUID checkpointId, IncidentAgentState state, long expectedVersion, Instant now) {
        DomainEvent event = new DomainEvent(UUID.nameUUIDFromBytes(
                (checkpointId + ":budget-exhausted").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                state.runId(), "TOKEN_BUDGET_EXHAUSTED", state.version(), now);
        return new CheckpointCommand(checkpointId, state, expectedVersion, List.of(), List.of(), List.of(event));
    }

    private static IncidentAgentState exhaustedState(
            IncidentAgentState current, RecoveryRequest request, Instant now) {
        var transition = StateMachines.INCIDENT_RUN.transition(
                current.status(), IncidentRunState.GENERATING_REPORT, current.version());
        if (transition.errorCode() != null) {
            throw new IllegalStateException(transition.errorCode());
        }
        List<String> missing = appendDistinct(current.missingEvidence(), request.missingWork());
        List<String> warnings = appendDistinct(current.warnings(), List.of(TOKEN_BUDGET_EXCEEDED));
        warnings = appendDistinct(warnings, request.limitations());
        warnings = appendDistinct(warnings, List.of("NEXT_STEP:" + request.nextStep()));
        TokenBudgetSnapshot budget = current.tokenBudget();
        return new IncidentAgentState(current.schemaVersion(), current.incidentId(), current.runId(),
                current.a2aContextId(), current.supervisorAgentSessionId(), transition.version(),
                transition.state(), current.outcome(), current.planArtifactId(), current.planVersion(),
                current.currentStepId(), current.steps(), current.evidenceIds(), current.hypothesisIds(),
                current.remediationPlanArtifactId(), current.approvalId(), budget, current.usage(),
                current.reactLoop(), missing, warnings, current.failureId(), current.finalReportArtifactId(),
                current.cancellationRequested(), current.deadline(), current.createdAt(), now);
    }

    private static long reclaimIfNeeded(
            long required, long remaining, long available, RecoveryAction action, List<RecoveryAction> actions) {
        long reclaimed = required > remaining ? Math.min(required - remaining, available) : 0;
        if (reclaimed > 0) {
            actions.add(action);
        }
        return required - reclaimed;
    }

    private static long divideRoundingUp(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static List<String> appendDistinct(List<String> existing, List<String> additions) {
        ArrayList<String> result = new ArrayList<>(existing);
        for (String value : additions) {
            if (value != null && !value.isBlank() && !result.contains(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    public static final class TokenBudgetExceededException extends IllegalStateException {
        public TokenBudgetExceededException() {
            super(TOKEN_BUDGET_EXCEEDED);
        }
    }
}
