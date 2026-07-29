package io.github.opspilot.server.generated;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Generated from docs/design/contracts/openapi/opspilot-v1.yaml. Do not edit. */
public final class ProductApiContract {
    public static final String SPEC_SHA256 = "973007c68f2716b0801c752576736f26957d6210dfcf5019d9360073422d358e";
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
