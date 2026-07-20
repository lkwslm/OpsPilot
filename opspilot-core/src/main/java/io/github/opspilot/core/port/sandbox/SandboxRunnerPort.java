package io.github.opspilot.core.port.sandbox;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

import java.time.Instant;
import java.util.List;

public interface SandboxRunnerPort {
    SandboxResult run(SandboxRequest request);

    record SandboxRequest(RunId runId, ArtifactId planArtifactId, Instant deadline) {
    }

    record SandboxResult(boolean accepted, String summary, List<ArtifactId> artifactIds, String errorCode) {
        public SandboxResult { artifactIds = List.copyOf(artifactIds); }
    }
}
