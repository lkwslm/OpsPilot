package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.HTTP;

public final class SpringActuatorHealthAdapter extends AbstractObservabilityAdapter {
    public SpringActuatorHealthAdapter(Path replayFile) {
        super(SourceDescriptors.of("phase0-actuator", HTTP, "spring-actuator-health",
                "observability-source://phase0/actuator", HEALTH), fileReader(replayFile), "application/json",
                java.util.Set.of("phase0/replay", "health/readiness-v1"));
    }

    public SpringActuatorHealthAdapter(URI endpoint) {
        super(SourceDescriptors.of("sample-actuator", HTTP, "spring-actuator-health",
                "observability-source://sample/actuator", HEALTH), httpReader(endpoint), "application/json",
                java.util.Set.of("phase0/replay", "health/readiness-v1"));
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        JsonNode root = MAPPER.readTree(content);
        if (!root.hasNonNull("status") || !root.path("components").isObject()) {
            throw new IllegalArgumentException("Invalid Actuator health response");
        }
        String status = root.path("status").asText();
        if ("UNKNOWN".equals(status) && root.path("components").isEmpty()) {
            return List.of();
        }
        return List.of(new ParsedObservation(
                HEALTH, Instant.now(), "Actuator status=" + status,
                Map.of("status", status, "componentCount", root.path("components").size())));
    }
}
