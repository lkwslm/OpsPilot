package io.github.opspilot.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.observability.JsonlLogAdapter;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceRef;
import io.github.opspilot.core.port.observability.ObservationContracts.ResourceType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes the real read-only observability adapter before the evidence AgentScope loop. */
final class Phase7EvidenceCollector {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final Path sourcePath;

    Phase7EvidenceCollector(Path sourcePath) {
        this.sourcePath = sourcePath;
    }

    String collect(String requestJson, UUID taskId) throws Exception {
        JsonNode request = JSON.readTree(requestJson);
        UUID incidentId = UUID.fromString(request.path("incidentId").asText());
        UUID runId = UUID.fromString(request.path("runId").asText());
        Instant windowStart = Instant.parse(request.path("windowStart").asText());
        Instant windowEnd = Instant.parse(request.path("windowEnd").asText());
        ResourceRef resource = new ResourceRef(
                "service:sample-system", ResourceType.SERVICE, "sample-system", "sample-system",
                "phase7", Map.of("composeService", "sample-system"));
        ObservationQuery query = new ObservationQuery(
                "log/errors-v1", ObservationContracts.sha256("level=ERROR"),
                windowStart, windowEnd, resource);
        var batch = new JsonlLogAdapter(sourcePath).query(
                query, SourceExecutionContext.authorizedUntil(Instant.now().plusSeconds(10)));
        var bundle = new RuntimeEvidenceNormalizer().normalizeRuntime(
                List.of(batch), new NormalizationContext(incidentId, runId, taskId));
        var evidence = bundle.evidence().stream().map(value -> Map.of(
                "evidenceId", value.evidenceId().toString(),
                "evidenceCode", value.evidenceCode(),
                "claim", value.claim(),
                "signalType", value.signalType().name(),
                "observedAt", value.windowStart().toString(),
                "artifactIds", value.artifactIds().stream().map(Object::toString).toList())).toList();
        return JSON.writeValueAsString(Map.ofEntries(
                Map.entry("schemaVersion", "1.0.0"),
                Map.entry("incidentId", incidentId.toString()),
                Map.entry("runId", runId.toString()),
                Map.entry("taskId", taskId.toString()),
                Map.entry("windowStart", windowStart.toString()),
                Map.entry("windowEnd", windowEnd.toString()),
                Map.entry("sourceId", batch.source().sourceId()),
                Map.entry("sourceKind", batch.source().sourceKind().name()),
                Map.entry("adapterId", batch.source().adapterId()),
                Map.entry("batchId", batch.batchId().toString()),
                Map.entry("artifactId", batch.rawArtifact().artifactId().toString()),
                Map.entry("rawArtifactSha256", batch.rawArtifact().sha256()),
                Map.entry("evidence", evidence)));
    }
}
