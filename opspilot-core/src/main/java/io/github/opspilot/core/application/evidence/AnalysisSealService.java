package io.github.opspilot.core.application.evidence;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reconciles all planned inputs and atomically seals one analysis version. */
public final class AnalysisSealService {
    private final SealRepository repository;

    public AnalysisSealService(SealRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public SealedRun seal(RunId runId, long expectedVersion, Instant sealedAt) {
        SealReadiness readiness = repository.readiness(runId);
        if (readiness.pendingArtifactTransactions() != 0) {
            throw new SealConflict("ARTIFACT_RECEPTION_PENDING");
        }
        List<String> unresolved = readiness.attempts().stream()
                .filter(attempt -> !attempt.terminal() && attempt.missingEvidenceCode() == null)
                .map(AttemptReadiness::attemptId)
                .toList();
        if (!unresolved.isEmpty()) {
            throw new SealConflict("ATTEMPT_NOT_RECONCILED");
        }
        List<String> missing = readiness.attempts().stream()
                .map(AttemptReadiness::missingEvidenceCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return repository.sealAtomically(runId, expectedVersion, sealedAt, missing);
    }

    public interface SealRepository {
        SealReadiness readiness(RunId runId);
        SealedRun sealAtomically(RunId runId, long expectedVersion, Instant sealedAt, List<String> missingEvidence);
    }

    public record AttemptReadiness(String attemptId, boolean terminal, String missingEvidenceCode) { }
    public record SealReadiness(List<AttemptReadiness> attempts, int pendingArtifactTransactions) {
        public SealReadiness { attempts = List.copyOf(attempts); }
    }
    public record SealedRun(RunId runId, long runVersion, Instant analysisSealedAt, List<String> missingEvidence) {
        public SealedRun { missingEvidence = List.copyOf(missingEvidence); }
    }
    public static final class SealConflict extends RuntimeException {
        public SealConflict(String message) { super(message); }
    }
}
