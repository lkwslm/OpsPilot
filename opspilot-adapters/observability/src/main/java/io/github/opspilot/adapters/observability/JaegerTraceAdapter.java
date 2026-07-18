package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TRACE;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.JAEGER;

public final class JaegerTraceAdapter extends AbstractObservabilityAdapter {
    public JaegerTraceAdapter(URI endpoint) {
        super(SourceDescriptors.of("phase0-jaeger", JAEGER, "jaeger-trace",
                "observability-source://phase0/jaeger", TRACE), httpReader(endpoint), "application/json");
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        JsonNode root = MAPPER.readTree(content);
        if (!root.path("data").isArray()) {
            throw new IllegalArgumentException("Invalid Jaeger response");
        }
        List<ParsedObservation> observations = new ArrayList<>();
        for (JsonNode trace : root.path("data")) {
            if (!trace.hasNonNull("traceID") || !trace.path("spans").isArray()) {
                throw new IllegalArgumentException("Invalid Jaeger trace");
            }
            JsonNode firstSpan = trace.path("spans").isEmpty() ? null : trace.path("spans").get(0);
            Instant observedAt = firstSpan == null
                    ? Instant.EPOCH : Instant.ofEpochMilli(firstSpan.path("startTime").asLong() / 1000);
            String operation = firstSpan == null ? "empty trace" : firstSpan.path("operationName").asText();
            observations.add(new ParsedObservation(
                    TRACE, observedAt, operation, Map.of(
                    "traceId", trace.path("traceID").asText(), "spanCount", trace.path("spans").size())));
        }
        return observations;
    }
}
