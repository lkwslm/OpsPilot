package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.METRIC;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.PROMETHEUS;

public final class PrometheusMetricAdapter extends AbstractObservabilityAdapter {
    private final URI endpoint;

    public PrometheusMetricAdapter(URI endpoint) {
        super(SourceDescriptors.of("phase0-prometheus", PROMETHEUS, "prometheus-metric",
                "observability-source://phase0/prometheus", METRIC), httpReader(endpoint), "application/json",
                java.util.Set.of("phase0/replay", "metric/http-v1", "metric/hikari-v1"));
        this.endpoint = endpoint;
    }

    @Override
    protected RawSourceReader readerFor(io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery query) {
        if ("phase0/replay".equals(query.templateId())) return super.readerFor(query);
        String job = query.resource().serviceName() == null
                ? query.resource().resourceId().replace("service:", "") : query.resource().serviceName();
        String expression = switch (query.templateId()) {
            case "metric/http-v1" -> "up{job=\"" + job + "\"}";
            case "metric/hikari-v1" -> "hikaricp_connections_active{job=\"" + job + "\"}";
            default -> throw new IllegalArgumentException("Unsupported metric query template");
        };
        String encoded = URLEncoder.encode(expression, StandardCharsets.UTF_8);
        return httpReader(endpoint.resolve("/api/v1/query?query=" + encoded));
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        JsonNode root = MAPPER.readTree(content);
        if (!"success".equals(root.path("status").asText()) || !root.path("data").path("result").isArray()) {
            throw new IllegalArgumentException("Invalid Prometheus response");
        }
        List<ParsedObservation> observations = new ArrayList<>();
        for (JsonNode result : root.path("data").path("result")) {
            JsonNode value = result.path("value");
            if (!value.isArray() || value.size() < 2) {
                throw new IllegalArgumentException("Invalid Prometheus sample");
            }
            String metric = result.path("metric").path("__name__").asText("metric");
            java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            attributes.put("metric", metric);
            attributes.put("value", value.get(1).asText());
            if (result.path("metric").hasNonNull("trace_id")) {
                attributes.put("otelTraceId", result.path("metric").path("trace_id").asText());
            }
            observations.add(new ParsedObservation(
                    METRIC, Instant.ofEpochSecond(value.get(0).asLong()), metric + "=" + value.get(1).asText(),
                    attributes));
        }
        return observations;
    }
}
