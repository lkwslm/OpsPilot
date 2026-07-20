package io.github.opspilot.core.application.incident;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.IncidentId;
import io.github.opspilot.core.domain.identity.DomainIds.RemoteTaskId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.domain.identity.DomainIds.StepId;
import io.github.opspilot.core.domain.state.StateMachines.A2aTaskState;
import io.github.opspilot.core.domain.state.StateMachines.IncidentRunState;

import java.time.Instant;
import java.util.List;

/** Framework-neutral application boundary. Controller and SDK DTOs terminate outside core. */
public final class IncidentUseCases {
    private IncidentUseCases() {
    }

    public interface CreateIncidentRun {
        RunResult create(CreateCommand command);
    }

    public interface StartIncidentRun {
        RunResult start(StartCommand command);
    }

    public interface ResumeIncidentRun {
        RunResult resume(ResumeCommand command);
    }

    public interface CancelIncidentRun {
        RunResult cancel(CancelCommand command);
    }

    public interface ReceiveA2aResult {
        RunResult receive(A2aResultCommand command);
    }

    public interface GenerateIncidentReport {
        ReportResult generate(GenerateReportCommand command);
    }

    public record CreateCommand(IncidentId incidentId, Instant deadline) {
    }

    public record StartCommand(RunId runId, List<PlannedStep> finitePlan) {
        public StartCommand { finitePlan = List.copyOf(finitePlan); }
    }

    public record ResumeCommand(RunId runId, long expectedVersion, String userInput) {
    }

    public record CancelCommand(RunId runId, long expectedVersion, String reasonCode) {
    }

    public record A2aResultCommand(
            RunId runId, StepId stepId, RemoteTaskId remoteTaskId, A2aTaskState state, ArtifactId artifactId) {
    }

    public record GenerateReportCommand(RunId runId, boolean limited, List<String> missingEvidence) {
        public GenerateReportCommand { missingEvidence = List.copyOf(missingEvidence); }
    }

    public record PlannedStep(StepId stepId, String capability, String actionFingerprint) {
    }

    public record RunResult(RunId runId, IncidentRunState state, long version, String outcomeCode) {
    }

    public record ReportResult(RunId runId, IncidentRunState state, ArtifactId reportArtifactId, boolean limited) {
    }
}
