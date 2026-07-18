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

    private final Limits limits;
    private final BooleanSupplier cancellationRequested;
    private final Clock clock;
    private final AgentScopeAuditCollector audit;
    private final Set<String> actionFingerprints = new HashSet<>();

    private int currentRound;
    private int consecutiveNoEvidenceRounds;
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
        String fingerprint = required("actionFingerprint", actionFingerprint);
        if (!actionFingerprints.add(fingerprint)) {
            return stop(DUPLICATE_ACTION);
        }
        return StopDecision.allowed();
    }

    /** Records validated evidence novelty immediately after a completed tool call. */
    public StopDecision afterToolCall(int newEvidenceCount) {
        if (terminal != null) {
            return terminal;
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

    StopDecision statePersistenceFailed() {
        return terminal == null ? stop(STATE_PERSISTENCE_FAILED) : terminal;
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

    public record Limits(int maxRounds, Instant deadline, int maxNoEvidenceRounds) {
        public Limits {
            if (maxRounds < 1) {
                throw new IllegalArgumentException("maxRounds must be positive");
            }
            Objects.requireNonNull(deadline, "deadline");
            if (maxNoEvidenceRounds < 1) {
                throw new IllegalArgumentException("maxNoEvidenceRounds must be positive");
            }
        }
    }

    public record StopDecision(boolean permitted, String reasonCode) {
        static StopDecision allowed() {
            return new StopDecision(true, null);
        }
    }
}
