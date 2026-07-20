package io.github.opspilot.core.port.artifact;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;

public interface ArtifactPort {
    ArtifactId store(RunId runId, String mediaType, byte[] content);
}
