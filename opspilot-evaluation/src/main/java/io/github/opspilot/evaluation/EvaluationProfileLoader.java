package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationProfile;
import io.github.opspilot.evaluation.EvaluationModels.GroundTruth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict loader for versioned, immutable evaluation configuration. */
public final class EvaluationProfileLoader {
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "schema_version", "profile_id", "runs_per_scenario", "model",
            "quality_thresholds", "hard_gates", "efficiency_limits");
    private final ObjectMapper json;

    public EvaluationProfileLoader(ObjectMapper json) { this.json = json; }

    public EvaluationProfile load(Path path) {
        JsonNode root = read(path);
        requireExactFields(root, PROFILE_FIELDS, "EVALUATION_PROFILE_FIELDS_INVALID");
        if (!"1.0.0".equals(text(root, "schema_version"))
                || root.path("runs_per_scenario").asInt() < 1
                || root.path("runs_per_scenario").asInt() > 100
                || root.path("model").path("temperature").asDouble(-1) < 0
                || root.path("model").path("temperature").asDouble(3) > 2
                || !root.path("model").path("fixed_config_snapshot").asBoolean(false)) {
            throw new IllegalArgumentException("EVALUATION_PROFILE_INVALID");
        }
        Map<String, Double> thresholds = doubles(root.path("quality_thresholds"), true);
        Map<String, Integer> limits = integers(root.path("efficiency_limits"));
        Map<String, Object> gates = new LinkedHashMap<>();
        root.path("hard_gates").fields().forEachRemaining(entry -> gates.put(entry.getKey(),
                entry.getValue().isBoolean() ? entry.getValue().booleanValue() : entry.getValue().doubleValue()));
        return new EvaluationProfile(text(root, "schema_version"), text(root, "profile_id"),
                text(root, "schema_version"), root.path("runs_per_scenario").asInt(),
                root.path("model").path("temperature").asDouble(), true,
                thresholds, gates, limits, sha256(canonical(root)));
    }

    public GroundTruth loadGroundTruth(Path path) {
        JsonNode root = read(path);
        if (!"1.0.0".equals(text(root, "schemaVersion"))) {
            throw new IllegalArgumentException("GROUND_TRUTH_SCHEMA_UNSUPPORTED");
        }
        Set<String> required = new LinkedHashSet<>();
        root.path("requiredEvidence").forEach(rule -> required.add(text(rule, "evidenceCode")));
        List<Set<String>> groups = new java.util.ArrayList<>();
        root.path("oneOfEvidenceGroups").forEach(group -> groups.add(strings(group)));
        return new GroundTruth(text(root, "scenarioId"), text(root, "rootCauseCode"), required,
                groups, Set.of(), strings(root.path("requiredToolNames")), strings(root.path("forbiddenToolNames")));
    }

    private JsonNode read(Path path) {
        try {
            byte[] content = Files.readAllBytes(path);
            return path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml")
                    ? new ObjectMapper(new YAMLFactory()).readTree(content) : json.readTree(content);
        }
        catch (IOException exception) { throw new IllegalArgumentException("EVALUATION_CONFIG_READ_FAILED", exception); }
    }

    private String canonical(JsonNode value) {
        try { return json.writer().writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalStateException("EVALUATION_CONFIG_SERIALIZATION_FAILED", exception); }
    }

    private static void requireExactFields(JsonNode root, Set<String> expected, String code) {
        Set<String> actual = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException(code);
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("EVALUATION_CONFIG_VALUE_MISSING:" + name);
        return value;
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Map<String, Double> doubles(JsonNode node, boolean ratio) {
        Map<String, Double> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            double value = entry.getValue().asDouble(Double.NaN);
            if (!Double.isFinite(value) || ratio && (value < 0 || value > 1)) {
                throw new IllegalArgumentException("EVALUATION_PROFILE_NUMBER_INVALID:" + entry.getKey());
            }
            values.put(entry.getKey(), value);
        });
        return values;
    }

    private static Map<String, Integer> integers(JsonNode node) {
        Map<String, Integer> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().canConvertToInt() || entry.getValue().asInt() < 1) {
                throw new IllegalArgumentException("EVALUATION_PROFILE_LIMIT_INVALID:" + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue().asInt());
        });
        return values;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
