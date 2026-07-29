"""Generate the small framework-neutral Java boundary from the frozen OpenAPI file."""

from __future__ import annotations

import hashlib
import pathlib
import re
import sys


EXPECTED_OPERATIONS = (
    "createIncident",
    "getIncident",
    "startIncidentRun",
    "resumeIncidentRun",
    "cancelIncidentRun",
    "getIncidentState",
    "getIncidentReport",
    "streamIncidentEvents",
    "listToolCalls",
    "decideApproval",
)


def main() -> None:
    spec_path = pathlib.Path(sys.argv[1])
    output_path = pathlib.Path(sys.argv[2])
    spec_bytes = spec_path.read_bytes()
    spec = spec_bytes.decode("utf-8")
    operations = tuple(re.findall(r"^\s+operationId:\s*([A-Za-z0-9_]+)\s*$", spec, re.MULTILINE))
    if operations != EXPECTED_OPERATIONS:
        raise SystemExit(f"OpenAPI operation drift: {operations!r}")
    if not spec.startswith("openapi: 3.1.0"):
        raise SystemExit("Only the frozen OpenAPI 3.1.0 contract is supported")
    digest = hashlib.sha256(spec_bytes).hexdigest()
    source = TEMPLATE.replace("__SPEC_SHA256__", digest)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(source, encoding="utf-8")


TEMPLATE = r'''package io.github.opspilot.server.generated;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Generated from docs/design/contracts/openapi/opspilot-v1.yaml. Do not edit. */
public final class ProductApiContract {
    public static final String SPEC_SHA256 = "__SPEC_SHA256__";
    public static final Set<String> OPERATION_IDS = Set.of(
            "createIncident", "getIncident", "startIncidentRun", "resumeIncidentRun",
            "cancelIncidentRun", "getIncidentState", "getIncidentReport",
            "streamIncidentEvents", "listToolCalls", "decideApproval");

    private ProductApiContract() { }

    public record CreateIncidentRequest(String targetSystemId, List<String> resourceIds,
            String scenarioId, String title, Severity severity, JsonNode ticket,
            List<UUID> inputArtifactIds) { }

    public record StartRunRequest(String modelConfigVersion, String evaluationProfile,
            Long tokenBudget, Integer deadlineSeconds) { }

    public record ResumeRunRequest(UUID runId, JsonNode input) { }

    public record RunCommandRequest(UUID runId, String reason) { }

    public record ApprovalDecisionRequest(UUID runId, ApprovalDecision decision, String reason) { }

    public record IncidentResponse(UUID requestId, UUID incidentId, String targetSystemId,
            List<String> resourceIds, String title, Severity severity, String status,
            UUID activeRunId, Instant createdAt) { }

    public record RunResponse(UUID requestId, UUID incidentId, UUID runId,
            RunStatus status, Outcome outcome, long version, ErrorResponse error) { }

    public record ToolCallSummary(UUID toolCallId, UUID runId, String toolName,
            ToolCallStatus status, String errorCode, Instant startedAt, Instant endedAt) { }

    public record ToolCallPage(List<ToolCallSummary> items, String nextPageToken) { }

    public record ErrorResponse(String type, String title, int status, String detail,
            String errorCode, UUID requestId, UUID incidentId, UUID runId,
            boolean retryable, UUID logArtifactId) { }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum ApprovalDecision { APPROVED, REJECTED }
    public enum Outcome { CONCLUSIVE, PARTIAL, INCONCLUSIVE }
    public enum ToolCallStatus { STARTED, SUCCEEDED, EMPTY, DENIED, FAILED }
    public enum RunStatus {
        CREATED, QUEUED, PLANNING, COLLECTING_EVIDENCE, ANALYZING_CODE,
        RETRIEVING_KNOWLEDGE, GENERATING_HYPOTHESES, VERIFYING_HYPOTHESES,
        GENERATING_REMEDIATION, WAITING_INPUT, WAITING_APPROVAL,
        RUNNING_SANDBOX_TEST, GENERATING_REPORT, CANCELLING,
        COMPLETED, FAILED, CANCELLED
    }
}
'''


if __name__ == "__main__":
    main()
