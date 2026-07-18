package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.LOG;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.FILE;

public final class JsonlLogAdapter extends AbstractObservabilityAdapter {
    public JsonlLogAdapter(Path path) {
        super(SourceDescriptors.of("phase0-jsonl", FILE, "jsonl-log",
                "observability-source://phase0/jsonl", LOG), fileReader(path), "application/x-ndjson");
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
            observations.add(new ParsedObservation(
                    LOG, Instant.parse(node.path("timestamp").asText()), node.path("message").asText(),
                    Map.of("level", node.path("level").asText("UNKNOWN"))));
        }
        return observations;
    }
}
