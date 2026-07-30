package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.CONFIG;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.LOG;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.METRIC;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TRACE;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.FILE;

public final class JsonlLogAdapter extends AbstractObservabilityAdapter {
    public JsonlLogAdapter(Path path) {
        super(SourceDescriptors.of("phase0-jsonl", FILE, "jsonl-log",
                "observability-source://phase0/jsonl", LOG, METRIC, TRACE, HEALTH, CONFIG),
                fileReader(path), "application/x-ndjson",
                java.util.Set.of("phase0/replay", "log/errors-v1"));
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        List<ParsedObservation> observations = new ArrayList<>();
        for (String line : new String(content, StandardCharsets.UTF_8).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            if (!node.hasNonNull("timestamp") || !node.hasNonNull("message")) {
                throw new IllegalArgumentException("Invalid JSONL record");
            }
            Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("level", node.path("level").asText("UNKNOWN"));
            for (String key : List.of(
                    "requestId", "traceId", "runId", "evidenceCode",
                    "sourceType", "service", "resourceId")) {
                if (node.hasNonNull(key)) attributes.put(key, node.path(key).asText());
            }
            if (node.hasNonNull("traceId")) attributes.put("otelTraceId", node.path("traceId").asText());
            var signalType = node.hasNonNull("sourceType")
                    ? io.github.opspilot.core.port.observability.ObservationContracts.SignalType.valueOf(
                            node.path("sourceType").asText())
                    : LOG;
            observations.add(new ParsedObservation(signalType,
                    Instant.parse(node.path("timestamp").asText()), node.path("message").asText(), attributes));
        }
        return observations;
    }
}
