package io.github.opspilot.runtime.agentscope;

import io.github.opspilot.core.port.agent.RuntimeAuditSink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

final class AgentScopeAuditCollector {

    private static final String REDACTED = "[REDACTED]";
    private static final List<String> SENSITIVE_KEYS = List.of(
            "secret", "token", "password", "authorization", "apikey", "api_key");
    private static final List<String> REASONING_KEYS = List.of(
            "reasoning", "chainofthought", "chain_of_thought", "hiddenreasoning", "hidden_reasoning");

    private final RuntimeAuditSink sink;
    private final AtomicLong sequence = new AtomicLong();

    AgentScopeAuditCollector(RuntimeAuditSink sink) {
        this.sink = sink;
    }

    void event(
            String type,
            int round,
            String actionFingerprint,
            Integer inputTokens,
            Integer outputTokens,
            String checkpointId,
            Boolean cancelled,
            Map<String, Object> attributes) {
        sink.append(new RuntimeAuditSink.AuditEvent(
                sequence.incrementAndGet(),
                type,
                round,
                actionFingerprint,
                inputTokens,
                outputTokens,
                checkpointId,
                cancelled,
                sanitizeMap(attributes)));
    }

    String actionFingerprint(String action, String toolName, Map<String, Object> arguments) {
        String canonical = action + "|" + toolName + "|" + canonicalValue(arguments);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    String checkpoint(String value) {
        return "sha256:" + actionFingerprint("CHECKPOINT", value, Map.of());
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> input) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (REASONING_KEYS.stream().anyMatch(normalized::contains)) {
                return;
            }
            if (SENSITIVE_KEYS.stream().anyMatch(normalized::contains)) {
                sanitized.put(key, REDACTED);
            } else {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringMap = new LinkedHashMap<>();
            map.forEach((key, child) -> stringMap.put(String.valueOf(key), child));
            return sanitizeMap(stringMap);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(sanitizeValue(item)));
            return List.copyOf(sanitized);
        }
        return value;
    }

    private String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), child));
            return sorted.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + canonicalValue(entry.getValue()))
                    .reduce("", (left, right) -> left + ";" + right);
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonicalValue(item)));
            return String.join(",", items);
        }
        return String.valueOf(value);
    }
}
