package io.github.opspilot.core.scheduling;

import io.github.opspilot.core.application.incident.IncidentUseCases;
import io.github.opspilot.core.application.incident.IncidentUseCases.A2aResultCommand;
import io.github.opspilot.core.application.incident.IncidentUseCases.PlannedStep;
import io.github.opspilot.core.application.incident.IncidentUseCases.ResumeCommand;
import io.github.opspilot.core.application.incident.IncidentUseCases.StartCommand;
import io.github.opspilot.core.application.incident.SupervisorService;
import io.github.opspilot.core.domain.identity.DomainIds.A2aTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.policy.SupervisorPolicy;
import io.github.opspilot.core.policy.SupervisorPolicy.Action;
import io.github.opspilot.core.policy.SupervisorPolicy.Evaluation;
import io.github.opspilot.core.policy.SupervisorPolicy.FrozenLimits;
import io.github.opspilot.core.policy.SupervisorPolicy.InputKind;
import io.github.opspilot.core.policy.SupervisorPolicy.ModelLimitRequest;
import io.github.opspilot.core.policy.SupervisorPolicy.Progress;
import io.github.opspilot.core.policy.SupervisorPolicy.StopReason;
import io.github.opspilot.core.policy.SupervisorPolicy.Usage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingContractTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final RunId RUN_ID = new RunId(uuid(1));
    private static final FrozenLimits LIMITS = new FrozenLimits(
            4, 6, 8, 4, 10_000, 1_000_000, 2, 4, NOW.plusSeconds(60));

    @Test
    void useCaseContractsAreFrameworkNeutral() {
        for (Class<?> nested : IncidentUseCases.class.getDeclaredClasses()) {
            for (RecordComponent component : nested.isRecord() ? nested.getRecordComponents() : new RecordComponent[0]) {
                String type = component.getGenericType().getTypeName();
                assertFalse(type.contains("spring") || type.contains("agentscope") || type.contains("a2a.sdk")
                        || type.contains("Controller"), type);
            }
        }
    }

    @Test
    void checkpointAlwaysPrecedesSingleDelegationAndFailureShortCircuits() {
        WorkflowFixture successful = new WorkflowFixture();
        SupervisorService service = new SupervisorService(successful, LIMITS);
        var result = service.start(new StartCommand(RUN_ID, List.of(
                step(1, "runtime-evidence"), step(2, "code-analysis"))));
        assertEquals(List.of("checkpoint:runtime-evidence", "delegate:runtime-evidence"), successful.events);
        assertEquals(IncidentRunState.COLLECTING_EVIDENCE, result.state());

        WorkflowFixture failing = new WorkflowFixture();
        failing.failCheckpoint = true;
        var failed = new SupervisorService(failing, LIMITS).start(
                new StartCommand(RUN_ID, List.of(step(1, "runtime-evidence"))));
        assertEquals(List.of("checkpoint:runtime-evidence"), failing.events);
        assertEquals(SupervisorService.CHECKPOINT_FAILED, failed.outcomeCode());
    }

    @Test
    void modelCannotRaiseFrozenMultidimensionalBudget() {
        SupervisorPolicy policy = new SupervisorPolicy();
        FrozenLimits result = policy.applyModelRequest(LIMITS, new ModelLimitRequest(999, Long.MAX_VALUE));
        assertEquals(LIMITS, result);

        var exhausted = policy.evaluate(evaluation(
                new Usage(4, 1, 1, 1, 100, 100), new Progress(Set.of(), Set.of(), 0), false, false, false));
        assertEquals(Action.GENERATE_LIMITED_REPORT, exhausted.action());
        assertEquals(StopReason.BUDGET_EXHAUSTED, exhausted.reason());
    }

    @Test
    void noveltyAndStopPriorityAreBoundedAndDeterministic() {
        Progress progress = new Progress(Set.of("same-action"), Set.of("same-evidence"), 1)
                .observe("same-action", Set.of("same-evidence"));
        var noProgress = new SupervisorPolicy().evaluate(evaluation(
                new Usage(1, 1, 1, 1, 100, 100), progress, false, false, false));
        assertEquals(StopReason.NO_PROGRESS, noProgress.reason());

        var cancellationWins = new SupervisorPolicy().evaluate(evaluation(
                new Usage(4, 6, 8, 4, 10_000, 1_000_000), progress, true, true, true));
        assertEquals(StopReason.CANCELLED, cancellationWins.reason());
        assertEquals(Action.STOP, cancellationWins.action());
    }

    @Test
    void waitingInputReplansAndCancellationMakesLateArtifactAuditOnly() {
        WorkflowFixture fixture = new WorkflowFixture();
        SupervisorService service = new SupervisorService(fixture, LIMITS);
        assertEquals(IncidentRunState.WAITING_INPUT,
                service.resume(new ResumeCommand(RUN_ID, 3, " ")).state());
        assertEquals(IncidentRunState.PLANNING,
                service.resume(new ResumeCommand(RUN_ID, 3, "production namespace")).state());
        assertEquals(List.of("replan:production namespace"), fixture.events);

        assertTrue(service.requestCancellation(RUN_ID, 3));
        assertFalse(service.requestCancellation(RUN_ID, 3));
        ArtifactId late = new ArtifactId(uuid(30));
        var result = service.receive(new A2aResultCommand(
                RUN_ID, new StepId(uuid(2)), new RemoteTaskId("code-agent", new A2aTaskId(uuid(3))),
                A2aTaskState.COMPLETED, late), true, 4);
        assertEquals("LATE_ARTIFACT_AUDIT_ONLY", result.outcomeCode());
        assertEquals(4, result.version());
        assertEquals(List.of(late), fixture.auditOnlyArtifacts);
    }

    @Test
    void waitingInputIsOnlyForAnswerableBusinessInputWithinContinuationLimit() {
        SupervisorPolicy policy = new SupervisorPolicy();
        Usage usage = new Usage(1, 1, 1, 1, 10, 10);
        Progress progress = new Progress(Set.of(), Set.of(), 0);

        assertEquals(Action.CONTINUE, policy.evaluate(new Evaluation(
                LIMITS, usage, progress, NOW, true, false, false,
                InputKind.TECHNICAL_UNAVAILABLE, 0, 2)).action());
        assertEquals(Action.CONTINUE, policy.evaluate(new Evaluation(
                LIMITS, usage, progress, NOW, true, false, false,
                InputKind.BUSINESS_ANSWERABLE, 2, 2)).action());
        assertEquals(Action.WAIT_FOR_INPUT, policy.evaluate(new Evaluation(
                LIMITS, usage, progress, NOW, true, false, false,
                InputKind.BUSINESS_ANSWERABLE, 1, 2)).action());
    }

    @Test
    void testPortsCoverRecoveryAndAllBoundedOutcomesWithoutOwningAnAgentLoop() throws IOException {
        SupervisorPolicy policy = new SupervisorPolicy();
        assertEquals(Action.CONTINUE, policy.evaluate(evaluation(
                new Usage(0, 0, 0, 0, 0, 0), new Progress(Set.of(), Set.of(), 0), false, false, false)).action());
        assertEquals(Action.CONTINUE, policy.evaluate(evaluation(
                new Usage(1, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 0), false, false, false)).action());
        assertEquals(StopReason.INPUT_REQUIRED, policy.evaluate(evaluation(
                new Usage(1, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 0), true, false, false)).reason());
        assertEquals(StopReason.BUDGET_EXHAUSTED, policy.evaluate(evaluation(
                new Usage(4, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 0), false, false, false)).reason());
        assertEquals(StopReason.NO_PROGRESS, policy.evaluate(evaluation(
                new Usage(1, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 2), false, false, false)).reason());
        assertEquals(StopReason.CRITICAL_FAILURE, policy.evaluate(evaluation(
                new Usage(1, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 0), false, true, false)).reason());
        assertEquals(StopReason.CANCELLED, policy.evaluate(evaluation(
                new Usage(1, 1, 1, 1, 10, 10), new Progress(Set.of(), Set.of(), 0), false, false, true)).reason());

        Path source = Path.of(System.getProperty("basedir"), "src", "main", "java", "io", "github",
                "opspilot", "core", "application", "incident", "SupervisorService.java");
        String java = Files.readString(source);
        assertFalse(java.contains("while ("), "core must not own a second business Agent loop");
        assertFalse(java.contains("AgentScope"), "core must not expose AgentScope runtime types");
    }

    private static Evaluation evaluation(
            Usage usage, Progress progress, boolean input, boolean failure, boolean cancelled) {
        return new Evaluation(LIMITS, usage, progress, NOW, input, failure, cancelled);
    }

    private static PlannedStep step(long id, String capability) {
        return new PlannedStep(new StepId(uuid(id + 10)), capability, "fingerprint-" + id);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    /** Test-only deterministic Port required by the OpenSpec; never a deployed Provider. */
    private static final class WorkflowFixture implements SupervisorService.WorkflowPort {
        private final List<String> events = new ArrayList<>();
        private final List<ArtifactId> auditOnlyArtifacts = new ArrayList<>();
        private boolean failCheckpoint;
        private boolean cancelled;

        @Override
        public void checkpointStep(RunId runId, PlannedStep step) {
            events.add("checkpoint:" + step.capability());
            if (failCheckpoint) {
                throw new IllegalStateException("injected checkpoint failure");
            }
        }

        @Override
        public void delegate(RunId runId, PlannedStep step) {
            events.add("delegate:" + step.capability());
        }

        @Override
        public void replanAfterInput(RunId runId, String input) {
            events.add("replan:" + input);
        }

        @Override
        public boolean compareAndSetCancellation(RunId runId, long expectedVersion) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public void auditLateArtifact(RunId runId, ArtifactId artifactId) {
            auditOnlyArtifacts.add(artifactId);
        }
    }
}
