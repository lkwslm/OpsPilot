package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.HTTP;

/** Product-neutral HTTP health source whose endpoint is fixed by connectionRef configuration. */
public final class HttpHealthAdapter extends AbstractObservabilityAdapter {
    public HttpHealthAdapter(URI endpoint) {
        super(SourceDescriptors.of("sample-http-health", HTTP, "http-health",
                        "observability-source://sample/http-health", HEALTH),
                httpReader(endpoint), "application/json",
                Set.of("phase0/replay", "health/readiness-v1"));
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        JsonNode root = MAPPER.readTree(content);
        String status = root.path("status").asText();
        if (status.isBlank()) throw new IllegalArgumentException("HTTP health status is required");
        return List.of(new ParsedObservation(
                HEALTH, Instant.now(), "HTTP health status=" + status,
                Map.of("status", status)));
    }
}
