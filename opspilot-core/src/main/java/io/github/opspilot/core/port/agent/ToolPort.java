package io.github.opspilot.core.port.agent;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;

import java.util.List;
import java.util.Map;

/** Project-owned boundary for tools exposed to an agent runtime. */
public interface ToolPort {

    String name();

    String description();

    Map<String, Object> inputSchema();

    ToolResult execute(Map<String, Object> input);

    record ToolResult(
            ToolStatus status,
            String summary,
            List<ArtifactId> artifactIds,
            List<EvidenceId> evidenceIds,
            String errorCode) {
        public ToolResult {
            artifactIds = List.copyOf(artifactIds);
            evidenceIds = List.copyOf(evidenceIds);
            if (summary != null && summary.length() > 512) {
                throw new IllegalArgumentException("tool summary exceeds 512 characters");
            }
        }

        /** Phase-0 source compatibility; new implementations should use the bounded contract. */
        public ToolResult(boolean success, String content) {
            this(success ? ToolStatus.SUCCEEDED : ToolStatus.FAILED,
                    content, List.of(), List.of(), success ? null : "TOOL_EXECUTION_FAILED");
        }

        public boolean success() { return status == ToolStatus.SUCCEEDED || status == ToolStatus.EMPTY; }

        public String content() { return summary; }
    }

    enum ToolStatus { SUCCEEDED, EMPTY, DENIED, FAILED }
}
