package io.github.opspilot.core.application.checkpoint;

import io.github.opspilot.core.domain.state.IncidentAgentState;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointCommand;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointConflict;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointStateReader;
import io.github.opspilot.core.port.repository.CheckpointContracts.CheckpointUnitOfWork;

import java.util.Objects;
import java.util.Optional;

/** Commits one atomic checkpoint and re-evaluates exactly once after a CAS conflict. */
public final class CheckpointService {
    private final CheckpointUnitOfWork unitOfWork;
    private final CheckpointStateReader stateReader;

    public CheckpointService(CheckpointUnitOfWork unitOfWork, CheckpointStateReader stateReader) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
    }

    public CommitResult commit(CheckpointCommand command, TransitionReevaluator reevaluator) {
        try {
            unitOfWork.commit(command);
            return new CommitResult(CommitOutcome.COMMITTED, command.state());
        } catch (CheckpointConflict conflict) {
            IncidentAgentState latest = stateReader.load(command.state().runId()).orElseThrow(
                    () -> new IllegalStateException("conflicting checkpoint state disappeared", conflict));
            Optional<CheckpointCommand> rejudged = reevaluator.rejudge(command, latest);
            if (rejudged.isEmpty()) {
                return new CommitResult(CommitOutcome.CONFLICT_REJECTED, latest);
            }
            CheckpointCommand retry = rejudged.get();
            if (retry.expectedVersion() != latest.version()) {
                throw new IllegalArgumentException("re-evaluated checkpoint must use the latest version");
            }
            unitOfWork.commit(retry);
            return new CommitResult(CommitOutcome.COMMITTED_AFTER_REEVALUATION, retry.state());
        }
    }

    public enum CommitOutcome { COMMITTED, COMMITTED_AFTER_REEVALUATION, CONFLICT_REJECTED }

    public record CommitResult(CommitOutcome outcome, IncidentAgentState state) {
    }

    @FunctionalInterface
    public interface TransitionReevaluator {
        Optional<CheckpointCommand> rejudge(CheckpointCommand stale, IncidentAgentState latest);
    }
}
