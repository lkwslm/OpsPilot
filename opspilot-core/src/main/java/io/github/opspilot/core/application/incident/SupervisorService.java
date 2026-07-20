package io.github.opspilot.core.application.incident;

import io.github.opspilot.core.application.incident.IncidentUseCases.A2aResultCommand;
import io.github.opspilot.core.application.incident.IncidentUseCases.PlannedStep;
import io.github.opspilot.core.application.incident.IncidentUseCases.ResumeCommand;
import io.github.opspilot.core.application.incident.IncidentUseCases.RunResult;
import io.github.opspilot.core.application.incident.IncidentUseCases.StartCommand;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;
import io.github.opspilot.core.policy.SupervisorPolicy.FrozenLimits;

import java.util.Objects;

/** Serial, checkpoint-first orchestration primitives used one step at a time. */
public final class SupervisorService {
    public static final String CHECKPOINT_FAILED = "CHECKPOINT_FAILED";

    private final WorkflowPort workflow;
    private final FrozenLimits limits;

    public SupervisorService(WorkflowPort workflow, FrozenLimits limits) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public RunResult start(StartCommand command) {
        if (command.finitePlan().isEmpty() || command.finitePlan().size() > limits.maxPlanSteps()) {
            throw new IllegalArgumentException("plan must be finite and bounded");
        }
        PlannedStep first = command.finitePlan().getFirst();
        try {
            workflow.checkpointStep(command.runId(), first);
        } catch (RuntimeException checkpointFailure) {
            return new RunResult(command.runId(), IncidentRunState.FAILED, 0, CHECKPOINT_FAILED);
        }
        workflow.delegate(command.runId(), first);
        return new RunResult(command.runId(), IncidentRunState.COLLECTING_EVIDENCE, 1, "STEP_DELEGATED");
    }

    public RunResult resume(ResumeCommand command) {
        if (command.userInput() == null || command.userInput().isBlank()) {
            return new RunResult(command.runId(), IncidentRunState.WAITING_INPUT,
                    command.expectedVersion(), "INPUT_REQUIRED");
        }
        workflow.replanAfterInput(command.runId(), command.userInput());
        return new RunResult(command.runId(), IncidentRunState.PLANNING,
                command.expectedVersion() + 1, "REPLAN_REQUIRED");
    }

    public boolean requestCancellation(RunId runId, long expectedVersion) {
        return workflow.compareAndSetCancellation(runId, expectedVersion);
    }

    public RunResult receive(A2aResultCommand command, boolean cancellationRequested, long currentVersion) {
        if (cancellationRequested) {
            workflow.auditLateArtifact(command.runId(), command.artifactId());
            return new RunResult(command.runId(), IncidentRunState.CANCELLING, currentVersion,
                    "LATE_ARTIFACT_AUDIT_ONLY");
        }
        return new RunResult(command.runId(), IncidentRunState.COLLECTING_EVIDENCE,
                currentVersion + 1, "RESULT_ACCEPTED");
    }

    public interface WorkflowPort {
        void checkpointStep(RunId runId, PlannedStep step);

        void delegate(RunId runId, PlannedStep step);

        void replanAfterInput(RunId runId, String input);

        boolean compareAndSetCancellation(RunId runId, long expectedVersion);

        void auditLateArtifact(RunId runId, ArtifactId artifactId);
    }
}
