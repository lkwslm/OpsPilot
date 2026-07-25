package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.CONFIG;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.FILE;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.HTTP;

/** Bounded config snapshot adapter for a fixed Spring endpoint or a controlled file. */
public final class ControlledConfigAdapter extends AbstractObservabilityAdapter {
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 4;
    private static final Set<String> SENSITIVE = Set.of(
            "password", "secret", "token", "credential", "apiKey", "privateKey");
    private final Set<String> allowedKeys;

    public ControlledConfigAdapter(Path path, Set<String> allowedKeys) {
        super(SourceDescriptors.of("sample-file-config", FILE, "controlled-file-config",
                        "observability-source://sample/file-config", CONFIG),
                fileReader(path), "application/json", Set.of("phase0/replay", "config/allowlist-v1"));
        this.allowedKeys = validatedAllowlist(allowedKeys);
    }

    public ControlledConfigAdapter(URI endpoint, Set<String> allowedKeys) {
        super(SourceDescriptors.of("sample-spring-config", HTTP, "controlled-spring-config",
                        "observability-source://sample/spring-config", CONFIG),
                httpReader(endpoint), "application/json", Set.of("phase0/replay", "config/allowlist-v1"));
        this.allowedKeys = validatedAllowlist(allowedKeys);
    }

    @Override
    protected List<ParsedObservation> parse(byte[] content) throws Exception {
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("Config snapshot exceeds limit");
        JsonNode root = MAPPER.readTree(content);
        if (!root.isObject() || depth(root) > MAX_DEPTH) throw new IllegalArgumentException("Invalid config snapshot");
        Map<String, Object> values = new TreeMap<>();
        root.fields().forEachRemaining(entry -> {
            if (!allowedKeys.contains(entry.getKey()) || sensitive(entry.getKey()) || !entry.getValue().isValueNode()) {
                throw new IllegalArgumentException("Config key is not allowed");
            }
            String value = entry.getValue().asText();
            if (value.length() > 512 || SensitiveDataRedactor.redact(value).contains("[REDACTED]")) {
                throw new IllegalArgumentException("Config value is sensitive or too large");
            }
            values.put(entry.getKey(), value);
        });
        if (values.isEmpty()) return List.of();
        String summary = new String(MAPPER.writeValueAsBytes(values), StandardCharsets.UTF_8);
        return List.of(new ParsedObservation(CONFIG, Instant.now(), summary, values));
    }

    private static Set<String> validatedAllowlist(Set<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > 32 || keys.stream().anyMatch(ControlledConfigAdapter::sensitive)) {
            throw new IllegalArgumentException("Invalid config allowlist");
        }
        return Set.copyOf(keys);
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE.stream().map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private static int depth(JsonNode node) {
        if (!node.isContainerNode() || node.isEmpty()) return 1;
        int max = 0;
        for (JsonNode child : node) max = Math.max(max, depth(child));
        return 1 + max;
    }
}
