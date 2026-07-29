package io.github.opspilot.core.state;

import io.github.opspilot.core.domain.identity.DomainIds;
import io.github.opspilot.core.domain.state.StateMachines;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.domain.state.StateMachines.StepAttemptState;
import io.github.opspilot.core.domain.value.DomainValues;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StateContractTest {
    @Test
    void everyStatePairMatchesTheExplicitAllowlists() {
        verifyAllPairs(AgentEndpointState.values(), StateMachines.ENDPOINT);
        verifyAllPairs(A2aTaskState.values(), StateMachines.A2A_TASK);
        verifyAllPairs(StepAttemptState.values(), StateMachines.STEP_ATTEMPT);
        verifyAllPairs(IncidentRunState.values(), StateMachines.INCIDENT_RUN);
    }

    @Test
    void progressEventsAndIllegalTransitionsKeepVersionUnchanged() {
        var progress = StateMachines.INCIDENT_RUN.transition(
                IncidentRunState.PLANNING, IncidentRunState.PLANNING, 8);
        assertFalse(progress.changed());
        assertEquals(8, progress.version());
        assertEquals(null, progress.errorCode());

        var invalid = StateMachines.INCIDENT_RUN.transition(
                IncidentRunState.COMPLETED, IncidentRunState.PLANNING, 8);
        assertFalse(invalid.changed());
        assertEquals(IncidentRunState.COMPLETED, invalid.state());
        assertEquals(8, invalid.version());
        assertEquals(StateMachines.INVALID_STATE_TRANSITION, invalid.errorCode());
    }

    @Test
    void a2aMappingRequiresLocalArtifactValidation() {
        assertEquals(StepAttemptState.WAITING_INPUT,
                StateMachines.mapA2aState(A2aTaskState.INPUT_REQUIRED));
        assertEquals(StepAttemptState.WAITING_AUTH,
                StateMachines.mapA2aState(A2aTaskState.AUTH_REQUIRED));
        assertEquals(StepAttemptState.VALIDATING_RESULT,
                StateMachines.mapA2aState(A2aTaskState.COMPLETED));
        assertThrows(IllegalArgumentException.class,
                () -> StateMachines.mapA2aState(A2aTaskState.UNSPECIFIED));

        for (int invalidIndex = 0; invalidIndex < 5; invalidIndex++) {
            boolean[] checks = {true, true, true, true, true};
            checks[invalidIndex] = false;
            var result = StateMachines.validateArtifact(
                    StepAttemptState.VALIDATING_RESULT,
                    new StateMachines.ArtifactValidation(
                            checks[0], checks[1], checks[2], checks[3], checks[4]), 4);
            assertEquals(StepAttemptState.FAILED, result.state());
        }
        assertEquals(StepAttemptState.COMPLETED,
                StateMachines.validateArtifact(StepAttemptState.VALIDATING_RESULT,
                        new StateMachines.ArtifactValidation(true, true, true, true, true), 4).state());
    }

    @Test
    void retryCreatesANewAttemptWithoutOverwritingTerminalHistory() {
        var runId = new DomainIds.RunId(UUID.randomUUID());
        var stepId = new DomainIds.StepId(UUID.randomUUID());
        var old = new StateMachines.StepAttempt(
                new DomainIds.StepAttemptId(runId, stepId, new DomainIds.Attempt(1)),
                StepAttemptState.FAILED, 7, new DomainIds.A2aTaskId(UUID.randomUUID()));
        var plan = old.scheduleRetry();

        assertSame(old, plan.prior());
        assertEquals(StepAttemptState.FAILED, old.state());
        assertEquals(7, old.version());
        assertEquals(StepAttemptState.RETRY_SCHEDULED, plan.schedulingState());
        assertEquals(2, plan.next().id().attempt().value());
        assertEquals(StepAttemptState.PENDING, plan.next().state());
        assertNotSame(old, plan.next());
    }

    @Test
    void endpointHealthIsIndependentFromTaskHistory() {
        var endpoint = new StateMachines.AgentEndpoint(
                "diagnosis-agent", "instance-a", AgentEndpointState.READY, null, true,
                new DomainValues.Sha256("sha256:" + "a".repeat(64)),
                Instant.parse("2026-07-18T10:00:00Z"),
                Instant.parse("2026-07-18T10:00:00Z"), 3);
        assertSame(endpoint, endpoint.onTaskFailure(new DomainIds.A2aTaskId(UUID.randomUUID())));
        assertEquals(Set.of(), StateMachines.ENDPOINT.successors(AgentEndpointState.DISABLED)
                .stream().filter(state -> state == AgentEndpointState.READY).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void incidentWaitingCancellationAndReportStatesRemainDistinct() {
        assertTrue(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.GENERATING_REMEDIATION, IncidentRunState.WAITING_APPROVAL));
        assertFalse(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.GENERATING_REMEDIATION, IncidentRunState.WAITING_INPUT));
        assertTrue(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.WAITING_INPUT, IncidentRunState.PLANNING));
        assertTrue(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.WAITING_APPROVAL, IncidentRunState.RUNNING_SANDBOX_TEST));
        assertTrue(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.PLANNING, IncidentRunState.CANCELLING));
        assertTrue(StateMachines.INCIDENT_RUN.allows(
                IncidentRunState.VERIFYING_HYPOTHESES, IncidentRunState.GENERATING_REPORT));
        assertEquals(IncidentRunState.COMPLETED,
                StateMachines.mapTopLevelTerminal(A2aTaskState.COMPLETED));
        assertEquals(IncidentRunState.FAILED,
                StateMachines.mapTopLevelTerminal(A2aTaskState.FAILED));
        assertEquals(IncidentRunState.CANCELLED,
                StateMachines.mapTopLevelTerminal(A2aTaskState.CANCELED));
        assertThrows(IllegalArgumentException.class,
                () -> StateMachines.mapTopLevelTerminal(A2aTaskState.WORKING));
    }

    private static <S extends Enum<S>> void verifyAllPairs(
            S[] states, StateMachines.TransitionPolicy<S> policy) {
        for (S from : states) {
            for (S to : states) {
                var result = policy.transition(from, to, 11);
                if (from == to) {
                    assertFalse(result.changed());
                    assertEquals(11, result.version());
                } else if (policy.allows(from, to)) {
                    assertTrue(result.changed(), from + " -> " + to);
                    assertEquals(12, result.version());
                    assertEquals(to, result.state());
                } else {
                    assertFalse(result.changed(), from + " -> " + to);
                    assertEquals(11, result.version());
                    assertEquals(from, result.state());
                    assertEquals(StateMachines.INVALID_STATE_TRANSITION, result.errorCode());
                }
            }
        }
    }
}
