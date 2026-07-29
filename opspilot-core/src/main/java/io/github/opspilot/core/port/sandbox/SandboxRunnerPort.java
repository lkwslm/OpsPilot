package io.github.opspilot.core.port.sandbox;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SandboxRunnerPort {
    SandboxResult run(SandboxRequest request);

    record SandboxRequest(
            RunId runId, ArtifactId planArtifactId, String testSuiteId,
            UUID approvalId, boolean approvalValid, boolean highRisk,
            boolean codeMutation, boolean arbitraryShellRequested,
            boolean networkRequested, Set<String> writableRoots,
            SandboxLimits limits, Instant deadline, CancellationToken cancellation) {
        public SandboxRequest {
            if (runId == null || planArtifactId == null) throw new IllegalArgumentException("run and plan are required");
            if (testSuiteId == null || testSuiteId.isBlank()) throw new IllegalArgumentException("testSuiteId is required");
            writableRoots = Set.copyOf(writableRoots);
            if (limits == null || deadline == null || cancellation == null) throw new IllegalArgumentException("limits, deadline, and cancellation are required");
        }
    }

    record SandboxResult(boolean accepted, String summary, List<ArtifactId> artifactIds, String errorCode) {
        public SandboxResult { artifactIds = List.copyOf(artifactIds); }
    }

    record SandboxLimits(int maxDurationSeconds, double maxCpu, int maxMemoryMb) {
        public SandboxLimits {
            if (maxDurationSeconds < 1 || maxCpu <= 0 || maxMemoryMb < 64) {
                throw new IllegalArgumentException("invalid sandbox limits");
            }
        }
    }

    @FunctionalInterface
    interface CancellationToken { boolean cancelled(); }
}
