package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.RuntimeAuditSink;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Middleware-style stop guard around AgentScope model and tool call boundaries. */
public final class AgentScopeExecutionGuard {

    public static final String MAX_ROUNDS = "MAX_ROUNDS";
    public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
    public static final String EXTERNAL_CANCELLED = "EXTERNAL_CANCELLED";
    public static final String DUPLICATE_ACTION = "DUPLICATE_ACTION";
    public static final String NO_PROGRESS = "NO_PROGRESS";
    public static final String STATE_PERSISTENCE_FAILED = "STATE_PERSISTENCE_FAILED";
    public static final String MODEL_BUDGET_EXHAUSTED = "MODEL_BUDGET_EXHAUSTED";
    public static final String TOOL_BUDGET_EXHAUSTED = "TOOL_BUDGET_EXHAUSTED";
    public static final String TOKEN_BUDGET_EXHAUSTED = "TOKEN_BUDGET_EXHAUSTED";

    private final Limits limits;
    private final BooleanSupplier cancellationRequested;
    private final Clock clock;
    private final AgentScopeAuditCollector audit;
    private final Set<String> actionFingerprints = new HashSet<>();

    private int currentRound;
    private int consecutiveNoEvidenceRounds;
    private int modelCalls;
    private int toolCalls;
    private int inputTokens;
    private int outputTokens;
    private int cachedTokens;
    private StopDecision terminal;

    public AgentScopeExecutionGuard(
            Limits limits,
            BooleanSupplier cancellationRequested,
            RuntimeAuditSink auditSink) {
        this(limits, cancellationRequested, auditSink, Clock.systemUTC());
    }

    AgentScopeExecutionGuard(
            Limits limits,
            BooleanSupplier cancellationRequested,
            RuntimeAuditSink auditSink,
            Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.cancellationRequested = Objects.requireNonNull(
                cancellationRequested, "cancellationRequested");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = new AgentScopeAuditCollector(Objects.requireNonNull(auditSink, "auditSink"));
    }

    /** Must be called immediately before each AgentScope model invocation. */
    public StopDecision beforeModelCall(int round) {
        if (terminal != null) {
            return terminal;
        }
        currentRound = round;
        if (cancellationRequested.getAsBoolean()) {
            return stop(EXTERNAL_CANCELLED);
        }
        if (!clock.instant().isBefore(limits.deadline())) {
            return stop(DEADLINE_EXCEEDED);
        }
        if (round > limits.maxRounds()) {
            return stop(MAX_ROUNDS);
        }
        if (modelCalls >= limits.maxModelCalls()) {
            return stop(MODEL_BUDGET_EXHAUSTED);
        }
        if (inputTokens + outputTokens >= limits.maxTokens()) {
            return stop(TOKEN_BUDGET_EXHAUSTED);
        }
        modelCalls++;
        return StopDecision.allowed();
    }

    /** Records provider usage immediately after a model call. */
    public StopDecision afterModelCall(int newInputTokens, int newOutputTokens, int newCachedTokens) {
        if (terminal != null) {
            return terminal;
        }
        if (cancellationRequested.getAsBoolean()) {
            return stop(EXTERNAL_CANCELLED);
        }
        if (!clock.instant().isBefore(limits.deadline())) {
            return stop(DEADLINE_EXCEEDED);
        }
        if (newInputTokens < 0 || newOutputTokens < 0 || newCachedTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
        inputTokens += newInputTokens;
        outputTokens += newOutputTokens;
        cachedTokens += newCachedTokens;
        if (inputTokens + outputTokens > limits.maxTokens()) {
            return stop(TOKEN_BUDGET_EXHAUSTED);
        }
        return StopDecision.allowed();
    }

    /** Must be called immediately before each AgentScope tool invocation. */
    public StopDecision beforeToolCall(String actionFingerprint) {
        if (terminal != null) {
            return terminal;
        }
        if (cancellationRequested.getAsBoolean()) {
            return stop(EXTERNAL_CANCELLED);
        }
        if (!clock.instant().isBefore(limits.deadline())) {
            return stop(DEADLINE_EXCEEDED);
        }
        if (toolCalls >= limits.maxToolCalls()) {
            return stop(TOOL_BUDGET_EXHAUSTED);
        }
        if (inputTokens + outputTokens >= limits.maxTokens()) {
            return stop(TOKEN_BUDGET_EXHAUSTED);
        }
        String fingerprint = required("actionFingerprint", actionFingerprint);
        if (!actionFingerprints.add(fingerprint)) {
            return stop(DUPLICATE_ACTION);
        }
        toolCalls++;
        return StopDecision.allowed();
    }

    /** Records validated evidence novelty immediately after a completed tool call. */
    public StopDecision afterToolCall(int newEvidenceCount) {
        if (terminal != null) {
            return terminal;
        }
        if (cancellationRequested.getAsBoolean()) {
            return stop(EXTERNAL_CANCELLED);
        }
        if (!clock.instant().isBefore(limits.deadline())) {
            return stop(DEADLINE_EXCEEDED);
        }
        if (newEvidenceCount < 0) {
            throw new IllegalArgumentException("newEvidenceCount must not be negative");
        }
        consecutiveNoEvidenceRounds = newEvidenceCount == 0
                ? consecutiveNoEvidenceRounds + 1
                : 0;
        if (consecutiveNoEvidenceRounds >= limits.maxNoEvidenceRounds()) {
            return stop(NO_PROGRESS);
        }
        return StopDecision.allowed();
    }

    public StopDecision status() {
        return terminal == null ? StopDecision.allowed() : terminal;
    }

    public Usage usage() {
        return new Usage(
                currentRound, modelCalls, toolCalls, inputTokens, outputTokens, cachedTokens);
    }

    StopDecision statePersistenceFailed() {
        return terminal == null ? stop(STATE_PERSISTENCE_FAILED) : terminal;
    }

    StopDecision deadlineExceeded() {
        return terminal == null ? stop(DEADLINE_EXCEEDED) : terminal;
    }

    StopDecision externalCancelled() {
        return terminal == null ? stop(EXTERNAL_CANCELLED) : terminal;
    }

    private StopDecision stop(String reasonCode) {
        terminal = new StopDecision(false, reasonCode);
        audit.event(
                "EXECUTION_STOPPED",
                currentRound,
                null,
                null,
                null,
                null,
                EXTERNAL_CANCELLED.equals(reasonCode),
                Map.of("reasonCode", reasonCode));
        return terminal;
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record Limits(
            int maxRounds,
            int maxModelCalls,
            int maxToolCalls,
            int maxTokens,
            Instant deadline,
            int maxNoEvidenceRounds) {
        public Limits {
            if (maxRounds < 1) {
                throw new IllegalArgumentException("maxRounds must be positive");
            }
            if (maxModelCalls < 1) {
                throw new IllegalArgumentException("maxModelCalls must be positive");
            }
            if (maxToolCalls < 0) {
                throw new IllegalArgumentException("maxToolCalls must not be negative");
            }
            if (maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            Objects.requireNonNull(deadline, "deadline");
            if (maxNoEvidenceRounds < 1) {
                throw new IllegalArgumentException("maxNoEvidenceRounds must be positive");
            }
        }

        public Limits(int maxRounds, Instant deadline, int maxNoEvidenceRounds) {
            this(maxRounds, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    deadline, maxNoEvidenceRounds);
        }
    }

    public record Usage(
            int rounds,
            int modelCalls,
            int toolCalls,
            int inputTokens,
            int outputTokens,
            int cachedTokens) {
    }

    public record StopDecision(boolean permitted, String reasonCode) {
        static StopDecision allowed() {
            return new StopDecision(true, null);
        }
    }
}
