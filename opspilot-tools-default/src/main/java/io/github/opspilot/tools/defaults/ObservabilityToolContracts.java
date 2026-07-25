package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceContracts.Evidence;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ObservabilityToolContracts {
    private ObservabilityToolContracts() { }

    public enum ToolStatus { SUCCEEDED, EMPTY, DENIED, FAILED }

    public record ToolRequest(
            String schemaVersion, String targetSystemId, UUID incidentId, UUID runId, UUID taskId,
            ResourceRef resource, Instant windowStart, Instant windowEnd, String queryTemplateId,
            Map<String, Object> parameters, String requiredSourceId, boolean explicitMultiSource,
            Set<String> allowedResourceIds) {
        public ToolRequest {
            parameters = Map.copyOf(parameters);
            allowedResourceIds = Set.copyOf(allowedResourceIds);
        }
    }

    public record ToolResult(
            ToolStatus status, String summary, List<UUID> observationBatchIds, UUID evidenceBundleId,
            List<UUID> evidenceIds, List<UUID> artifactIds, List<String> missing, String failureCode) {
        public ToolResult {
            observationBatchIds = List.copyOf(observationBatchIds);
            evidenceIds = List.copyOf(evidenceIds);
            artifactIds = List.copyOf(artifactIds);
            missing = List.copyOf(missing);
            if (summary != null && summary.length() > 512) summary = summary.substring(0, 512);
        }
    }

    @FunctionalInterface
    public interface ToolAuthorizationPort {
        boolean allowed(String targetSystemId, UUID runId, ResourceRef resource);
    }

    @FunctionalInterface
    public interface ToolAuditSink {
        void record(String toolName, UUID runId, ToolStatus outcome);
    }
}
