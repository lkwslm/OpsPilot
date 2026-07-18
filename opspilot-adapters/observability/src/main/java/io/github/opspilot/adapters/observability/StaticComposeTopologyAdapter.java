package io.github.opspilot.adapters.observability;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TOPOLOGY;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.FILE;

public final class StaticComposeTopologyAdapter extends AbstractObservabilityAdapter {
    public StaticComposeTopologyAdapter(Path path) {
        super(SourceDescriptors.of("phase0-compose", FILE, "static-compose-topology",
                "observability-source://phase0/compose", TOPOLOGY), fileReader(path), "application/yaml");
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) {
        String yaml = new String(content, StandardCharsets.UTF_8);
        if (!yaml.lines().anyMatch(line -> line.strip().startsWith("services:"))) {
            throw new IllegalArgumentException("Compose services section is required");
        }
        if (yaml.lines().anyMatch(line -> line.strip().equals("services: {}"))) {
            return List.of();
        }
        List<ParsedObservation> observations = new ArrayList<>();
        boolean inServices = false;
        for (String line : yaml.lines().toList()) {
            if (line.strip().equals("services:")) {
                inServices = true;
                continue;
            }
            if (inServices && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (inServices && line.matches("^  [A-Za-z0-9._-]+:\\s*$")) {
                String service = line.strip().replace(":", "");
                observations.add(new ParsedObservation(
                        TOPOLOGY, Instant.now(), "Compose service=" + service, Map.of("service", service)));
            }
        }
        return observations;
    }
}
