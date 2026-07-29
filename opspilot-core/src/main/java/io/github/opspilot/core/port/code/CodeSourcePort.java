package io.github.opspilot.core.port.code;

import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;
import io.github.opspilot.core.port.code.CodeContracts.CodeSourceExecutionContext;

import java.time.Instant;

public interface CodeSourcePort {
    CodeSnapshot materialize(CodeSourceRequest request);

    record CodeSourceRequest(
            String repositoryId, String commitSha, CodeSourceExecutionContext executionContext) {
        public CodeSourceRequest {
            if (repositoryId == null || repositoryId.isBlank()) throw new IllegalArgumentException("repositoryId must not be blank");
            if (commitSha == null || !commitSha.matches("[a-f0-9]{40}")) {
                throw new IllegalArgumentException("commitSha must be a full lowercase SHA");
            }
            if (executionContext == null) throw new IllegalArgumentException("executionContext must not be null");
        }

        public CodeSourceRequest(String repositoryId, String revision, String credentialRef, Instant deadline) {
            this(repositoryId, revision, new CodeSourceExecutionContext(
                    "legacy-source", CodeContracts.SourceKind.GITHUB, "legacy-adapter", "1.0.0",
                    credentialRef.startsWith("secret://") ? credentialRef : "secret://" + credentialRef, deadline));
        }

        public String revision() { return commitSha; }
        public String credentialRef() { return executionContext.connectionRef(); }
        public Instant deadline() { return executionContext.deadline(); }
    }
}
