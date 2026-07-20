package io.github.opspilot.core.port.code;

import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;

import java.time.Instant;

public interface CodeSourcePort {
    CodeSnapshot materialize(CodeSourceRequest request);

    record CodeSourceRequest(String repositoryId, String revision, String credentialRef, Instant deadline) {
    }
}
