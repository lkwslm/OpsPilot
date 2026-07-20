package io.github.opspilot.core.domain.state;

import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.Attempt;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepAttemptId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.value.DomainValues.Sha256;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Four separate state planes and their authoritative transition allowlists. */
public final class StateMachines {
    public static final String INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION";

    private StateMachines() {
    }

    public enum AgentEndpointState { UNKNOWN, PROBING, READY, UNAVAILABLE, DRAINING, DISABLED }

    public enum EndpointReasonCode {
        CARD_INVALID, PROTOCOL_INCOMPATIBLE, UNTRUSTED_IDENTITY, SKILL_MISSING, AUTH_FAILED,
        HEALTH_CHECK_FAILED, CIRCUIT_OPEN, CAPACITY_EXHAUSTED, OPERATOR_DISABLED
    }

    public enum A2aTaskState {
        UNSPECIFIED, SUBMITTED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED,
        COMPLETED, FAILED, CANCELED, REJECTED
    }

    public enum StepAttemptState {
        PENDING, DISPATCHING, RECONCILING, QUEUED, RUNNING, WAITING_INPUT, WAITING_AUTH,
        VALIDATING_RESULT, RETRY_SCHEDULED, CANCEL_REQUESTED,
        COMPLETED, FAILED, CANCELLED, REJECTED, SKIPPED
    }

    public enum IncidentRunState {
        CREATED, QUEUED, PLANNING, COLLECTING_EVIDENCE, ANALYZING_CODE, RETRIEVING_KNOWLEDGE,
        GENERATING_HYPOTHESES, VERIFYING_HYPOTHESES, GENERATING_REMEDIATION,
        WAITING_INPUT, WAITING_APPROVAL, RUNNING_SANDBOX_TEST, GENERATING_REPORT,
        CANCELLING, COMPLETED, FAILED, CANCELLED
    }

    public static final TransitionPolicy<AgentEndpointState> ENDPOINT = policy(
            AgentEndpointState.class,
            edges(
                    edge(AgentEndpointState.UNKNOWN, AgentEndpointState.PROBING, AgentEndpointState.DISABLED),
                    edge(AgentEndpointState.PROBING, AgentEndpointState.READY, AgentEndpointState.UNAVAILABLE,
                            AgentEndpointState.DISABLED),
                    edge(AgentEndpointState.READY, AgentEndpointState.UNAVAILABLE, AgentEndpointState.DRAINING,
                            AgentEndpointState.DISABLED),
                    edge(AgentEndpointState.UNAVAILABLE, AgentEndpointState.PROBING, AgentEndpointState.DISABLED),
                    edge(AgentEndpointState.DRAINING, AgentEndpointState.UNAVAILABLE, AgentEndpointState.DISABLED),
                    edge(AgentEndpointState.DISABLED, AgentEndpointState.PROBING)));

    public static final TransitionPolicy<A2aTaskState> A2A_TASK = policy(
            A2aTaskState.class,
            edges(
                    edge(A2aTaskState.SUBMITTED, A2aTaskState.WORKING, A2aTaskState.INPUT_REQUIRED,
                            A2aTaskState.AUTH_REQUIRED, A2aTaskState.COMPLETED, A2aTaskState.FAILED,
                            A2aTaskState.CANCELED, A2aTaskState.REJECTED),
                    edge(A2aTaskState.WORKING, A2aTaskState.INPUT_REQUIRED, A2aTaskState.AUTH_REQUIRED,
                            A2aTaskState.COMPLETED, A2aTaskState.FAILED, A2aTaskState.CANCELED,
                            A2aTaskState.REJECTED),
                    edge(A2aTaskState.INPUT_REQUIRED, A2aTaskState.WORKING, A2aTaskState.FAILED,
                            A2aTaskState.CANCELED, A2aTaskState.REJECTED),
                    edge(A2aTaskState.AUTH_REQUIRED, A2aTaskState.WORKING, A2aTaskState.FAILED,
                            A2aTaskState.CANCELED, A2aTaskState.REJECTED)));

    public static final TransitionPolicy<StepAttemptState> STEP_ATTEMPT = policy(
            StepAttemptState.class,
            edges(
                    edge(StepAttemptState.PENDING, StepAttemptState.DISPATCHING, StepAttemptState.SKIPPED,
                            StepAttemptState.CANCELLED),
                    edge(StepAttemptState.DISPATCHING, StepAttemptState.QUEUED, StepAttemptState.RUNNING,
                            StepAttemptState.RECONCILING, StepAttemptState.WAITING_INPUT,
                            StepAttemptState.WAITING_AUTH, StepAttemptState.VALIDATING_RESULT,
                            StepAttemptState.FAILED, StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.RECONCILING, StepAttemptState.QUEUED, StepAttemptState.RUNNING,
                            StepAttemptState.WAITING_INPUT, StepAttemptState.WAITING_AUTH,
                            StepAttemptState.VALIDATING_RESULT, StepAttemptState.FAILED,
                            StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.QUEUED, StepAttemptState.RUNNING, StepAttemptState.WAITING_INPUT,
                            StepAttemptState.WAITING_AUTH, StepAttemptState.VALIDATING_RESULT,
                            StepAttemptState.FAILED, StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.RUNNING, StepAttemptState.WAITING_INPUT, StepAttemptState.WAITING_AUTH,
                            StepAttemptState.VALIDATING_RESULT, StepAttemptState.FAILED,
                            StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.WAITING_INPUT, StepAttemptState.RUNNING, StepAttemptState.FAILED,
                            StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.WAITING_AUTH, StepAttemptState.RUNNING, StepAttemptState.FAILED,
                            StepAttemptState.REJECTED, StepAttemptState.CANCEL_REQUESTED),
                    edge(StepAttemptState.VALIDATING_RESULT, StepAttemptState.COMPLETED, StepAttemptState.FAILED),
                    edge(StepAttemptState.CANCEL_REQUESTED, StepAttemptState.CANCELLED,
                            StepAttemptState.COMPLETED, StepAttemptState.FAILED)));

    public static final TransitionPolicy<IncidentRunState> INCIDENT_RUN = incidentPolicy();

    public static StepAttemptState mapA2aState(A2aTaskState state) {
        return switch (state) {
            case UNSPECIFIED -> throw new IllegalArgumentException("INVALID_AGENT_RESPONSE");
            case SUBMITTED -> StepAttemptState.QUEUED;
            case WORKING -> StepAttemptState.RUNNING;
            case INPUT_REQUIRED -> StepAttemptState.WAITING_INPUT;
            case AUTH_REQUIRED -> StepAttemptState.WAITING_AUTH;
            case COMPLETED -> StepAttemptState.VALIDATING_RESULT;
            case FAILED -> StepAttemptState.FAILED;
            case CANCELED -> StepAttemptState.CANCELLED;
            case REJECTED -> StepAttemptState.REJECTED;
        };
    }

    public static IncidentRunState mapTopLevelTerminal(A2aTaskState state) {
        return switch (state) {
            case COMPLETED -> IncidentRunState.COMPLETED;
            case FAILED, REJECTED -> IncidentRunState.FAILED;
            case CANCELED -> IncidentRunState.CANCELLED;
            default -> throw new IllegalArgumentException("top-level A2A Task is not terminal");
        };
    }

    public record ArtifactValidation(
            boolean mediaTypeValid,
            boolean schemaValid,
            boolean hashValid,
            boolean taskIdentityValid,
            boolean authorized) {
        public boolean accepted() {
            return mediaTypeValid && schemaValid && hashValid && taskIdentityValid && authorized;
        }
    }

    public static TransitionResult<StepAttemptState> validateArtifact(
            StepAttemptState current, ArtifactValidation validation, long version) {
        if (current != StepAttemptState.VALIDATING_RESULT) {
            return TransitionResult.invalid(current, version);
        }
        return STEP_ATTEMPT.transition(current,
                validation.accepted() ? StepAttemptState.COMPLETED : StepAttemptState.FAILED, version);
    }

    public record StepAttempt(StepAttemptId id, StepAttemptState state, long version, A2aTaskId remoteTaskId) {
        public StepAttempt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
            if (version < 0) {
                throw new IllegalArgumentException("version must be non-negative");
            }
        }

        public RetryPlan scheduleRetry() {
            if (!EnumSet.of(StepAttemptState.FAILED, StepAttemptState.REJECTED).contains(state)) {
                throw new IllegalStateException("only a retryable terminal attempt can schedule a retry");
            }
            StepAttemptId nextId = new StepAttemptId(id.runId(), id.stepId(), new Attempt(id.attempt().value() + 1));
            return new RetryPlan(this, StepAttemptState.RETRY_SCHEDULED,
                    new StepAttempt(nextId, StepAttemptState.PENDING, 0, null));
        }
    }

    public record RetryPlan(StepAttempt prior, StepAttemptState schedulingState, StepAttempt next) {
    }

    public record AgentEndpoint(
            String remoteAgentId,
            String instanceId,
            AgentEndpointState state,
            EndpointReasonCode reasonCode,
            boolean retryable,
            Sha256 cardDigest,
            Instant lastProbeAt,
            Instant lastSuccessfulProbeAt,
            long version) {
        public AgentEndpoint {
            Objects.requireNonNull(remoteAgentId, "remoteAgentId");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(cardDigest, "cardDigest");
            Objects.requireNonNull(lastProbeAt, "lastProbeAt");
            if (state == AgentEndpointState.READY && lastSuccessfulProbeAt == null) {
                throw new IllegalArgumentException("READY endpoint requires a successful probe time");
            }
        }

        /** A Task failure is a Task fact and cannot mutate endpoint health. */
        public AgentEndpoint onTaskFailure(A2aTaskId ignoredTaskId) {
            Objects.requireNonNull(ignoredTaskId, "taskId");
            return this;
        }
    }

    public record TransitionResult<S extends Enum<S>>(
            boolean changed, S state, long version, String errorCode) {
        static <S extends Enum<S>> TransitionResult<S> invalid(S state, long version) {
            return new TransitionResult<>(false, state, version, INVALID_STATE_TRANSITION);
        }
    }

    public static final class TransitionPolicy<S extends Enum<S>> {
        private final Map<S, Set<S>> allowed;

        private TransitionPolicy(Map<S, Set<S>> allowed) {
            this.allowed = allowed;
        }

        public TransitionResult<S> transition(S current, S next, long version) {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(next, "next");
            if (current == next) {
                return new TransitionResult<>(false, current, version, null);
            }
            if (!allows(current, next)) {
                return TransitionResult.invalid(current, version);
            }
            return new TransitionResult<>(true, next, version + 1, null);
        }

        public boolean allows(S current, S next) {
            return allowed.getOrDefault(current, Set.of()).contains(next);
        }

        public Set<S> successors(S current) {
            return allowed.getOrDefault(current, Set.of());
        }
    }

    @SafeVarargs
    private static <S extends Enum<S>> Map.Entry<S, Set<S>> edge(S from, S... to) {
        return Map.entry(from, Set.of(to));
    }

    @SafeVarargs
    private static <S extends Enum<S>> Map<S, Set<S>> edges(Map.Entry<S, Set<S>>... entries) {
        Map<S, Set<S>> values = new java.util.HashMap<>();
        for (Map.Entry<S, Set<S>> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private static <S extends Enum<S>> TransitionPolicy<S> policy(Class<S> type, Map<S, Set<S>> values) {
        Map<S, Set<S>> copy = new EnumMap<>(type);
        values.forEach((state, successors) -> copy.put(state, Collections.unmodifiableSet(EnumSet.copyOf(successors))));
        return new TransitionPolicy<>(Collections.unmodifiableMap(copy));
    }

    private static TransitionPolicy<IncidentRunState> incidentPolicy() {
        Map<IncidentRunState, Set<IncidentRunState>> values = new EnumMap<>(IncidentRunState.class);
        add(values, IncidentRunState.CREATED, IncidentRunState.QUEUED, IncidentRunState.CANCELLED);
        add(values, IncidentRunState.QUEUED, IncidentRunState.PLANNING);
        add(values, IncidentRunState.PLANNING, IncidentRunState.COLLECTING_EVIDENCE,
                IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.COLLECTING_EVIDENCE, IncidentRunState.ANALYZING_CODE,
                IncidentRunState.RETRIEVING_KNOWLEDGE, IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.ANALYZING_CODE, IncidentRunState.RETRIEVING_KNOWLEDGE,
                IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.RETRIEVING_KNOWLEDGE, IncidentRunState.GENERATING_HYPOTHESES,
                IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.GENERATING_HYPOTHESES, IncidentRunState.VERIFYING_HYPOTHESES,
                IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.VERIFYING_HYPOTHESES, IncidentRunState.COLLECTING_EVIDENCE,
                IncidentRunState.GENERATING_REMEDIATION, IncidentRunState.GENERATING_REPORT,
                IncidentRunState.WAITING_INPUT);
        add(values, IncidentRunState.GENERATING_REMEDIATION, IncidentRunState.WAITING_APPROVAL,
                IncidentRunState.GENERATING_REPORT);
        add(values, IncidentRunState.WAITING_INPUT, IncidentRunState.PLANNING,
                IncidentRunState.GENERATING_REPORT);
        add(values, IncidentRunState.WAITING_APPROVAL, IncidentRunState.RUNNING_SANDBOX_TEST,
                IncidentRunState.GENERATING_REPORT);
        add(values, IncidentRunState.RUNNING_SANDBOX_TEST, IncidentRunState.GENERATING_REPORT);
        add(values, IncidentRunState.GENERATING_REPORT, IncidentRunState.COMPLETED);
        add(values, IncidentRunState.CANCELLING, IncidentRunState.CANCELLED);

        EnumSet<IncidentRunState> terminal = EnumSet.of(
                IncidentRunState.COMPLETED, IncidentRunState.FAILED, IncidentRunState.CANCELLED);
        for (IncidentRunState state : IncidentRunState.values()) {
            if (terminal.contains(state)) {
                continue;
            }
            values.computeIfAbsent(state, ignored -> EnumSet.noneOf(IncidentRunState.class));
            if (state != IncidentRunState.CREATED && state != IncidentRunState.CANCELLING) {
                values.get(state).add(IncidentRunState.CANCELLING);
            }
            values.get(state).add(IncidentRunState.FAILED);
        }
        Map<IncidentRunState, Set<IncidentRunState>> immutable = new EnumMap<>(IncidentRunState.class);
        values.forEach((state, successors) -> immutable.put(state, Set.copyOf(successors)));
        return new TransitionPolicy<>(Collections.unmodifiableMap(immutable));
    }

    private static void add(
            Map<IncidentRunState, Set<IncidentRunState>> values,
            IncidentRunState from,
            IncidentRunState... to) {
        values.computeIfAbsent(from, ignored -> EnumSet.noneOf(IncidentRunState.class)).addAll(Set.of(to));
    }
}
