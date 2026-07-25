package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TRACE;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.JAEGER;

/** Shared Jaeger JSON query mapper. The legacy type remains a v1-compatible alias. */
public class JaegerTraceAdapter extends AbstractObservabilityAdapter {
    private final URI endpoint;

    public JaegerTraceAdapter(URI endpoint) {
        this(endpoint, v1Descriptor());
    }

    protected JaegerTraceAdapter(URI endpoint, SourceDescriptor descriptor) {
        super(descriptor, httpReader(endpoint), "application/json",
                java.util.Set.of("phase0/replay", "trace/service-v1"));
        this.endpoint = endpoint;
    }

    static SourceDescriptor v1Descriptor() {
        return SourceDescriptors.of("phase0-jaeger-v1", JAEGER, "jaeger-trace-v1",
                "observability-source://phase0/jaeger-v1", TRACE);
    }

    static SourceDescriptor v2Descriptor() {
        return SourceDescriptors.of("phase0-jaeger-v2", JAEGER, "jaeger-trace-v2",
                "observability-source://phase0/jaeger-v2", TRACE);
    }

    @Override
    protected RawSourceReader readerFor(io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery query) {
        if ("phase0/replay".equals(query.templateId())) return super.readerFor(query);
        String service = query.resource().serviceName() == null
                ? query.resource().resourceId().replace("service:", "") : query.resource().serviceName();
        String encoded = URLEncoder.encode(service, StandardCharsets.UTF_8);
        long startMicros = query.windowStart().toEpochMilli() * 1000;
        long endMicros = query.windowEnd().toEpochMilli() * 1000;
        return httpReader(endpoint.resolve("/api/traces?service=" + encoded
                + "&start=" + startMicros + "&end=" + endMicros + "&limit=20"));
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
            java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("traceId", trace.path("traceID").asText());
            attributes.put("otelTraceId", trace.path("traceID").asText());
            attributes.put("spanCount", trace.path("spans").size());
            if (firstSpan != null && firstSpan.hasNonNull("spanID")) {
                attributes.put("otelSpanId", firstSpan.path("spanID").asText());
            }
            observations.add(new ParsedObservation(TRACE, observedAt, operation, attributes));
        }
        return observations;
    }
}
