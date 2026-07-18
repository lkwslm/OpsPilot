package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.METRIC;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.PROMETHEUS;

public final class PrometheusMetricAdapter extends AbstractObservabilityAdapter {
    public PrometheusMetricAdapter(URI endpoint) {
        super(SourceDescriptors.of("phase0-prometheus", PROMETHEUS, "prometheus-metric",
                "observability-source://phase0/prometheus", METRIC), httpReader(endpoint), "application/json");
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
            observations.add(new ParsedObservation(
                    METRIC, Instant.ofEpochSecond(value.get(0).asLong()), metric + "=" + value.get(1).asText(),
                    Map.of("metric", metric, "value", value.get(1).asText())));
        }
        return observations;
    }
}
