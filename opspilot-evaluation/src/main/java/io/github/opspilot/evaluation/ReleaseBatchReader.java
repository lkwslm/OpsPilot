package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads a sealed 3x5 quality batch and fails closed before aggregation. */
public final class ReleaseBatchReader {
    private static final Set<String> SCENARIOS = Set.of(
            "database-pool-exhausted-order",
            "dependency-latency-inventory",
            "service-instance-stopped-inventory");
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "rca-json", "rca-markdown", "evaluation", "provider-calls", "tool-calls",
            "a2a-calls", "usage-ledger", "state-events", "citations", "artifact-index");
    private static final Set<String> PROFESSIONAL_AGENTS = Set.of(
            "evidence-collector", "code-analysis", "knowledge", "diagnosis", "remediation");

    private final ObjectMapper json;

    public ReleaseBatchReader(ObjectMapper json) {
        this.json = json;
    }

    public ReleaseBatch read(
            Path planPath,
            Path ledgerPath,
            Path evidenceRoot,
            String expectedProfileId,
            String expectedProfileDigest,
            String expectedSnapshotDigest,
            RunRecomputer recomputer) {
        try {
            JsonNode plan = json.readTree(planPath.toFile());
            String batchId = requiredText(plan, "releaseBatchId");
            if (!"RELEASE_QUALITY".equals(requiredText(plan, "runPurpose"))
                    || plan.path("totalSlots").asInt(-1) != 15
                    || plan.path("runsPerScenario").asInt(-1) != 5
                    || !expectedProfileId.equals(requiredText(plan, "evaluationProfileId"))
                    || !expectedSnapshotDigest.equals(requiredText(plan, "snapshotDigest"))) {
                invalid("plan identity");
            }
            Map<String, JsonNode> slots = readSlots(plan.path("slots"), expectedSnapshotDigest);
            Map<String, JsonNode> runs = readLedger(ledgerPath, batchId, expectedSnapshotDigest);
            if (runs.size() != 15) invalid("ledger run count");

            List<ReleaseRun> results = new ArrayList<>();
            Set<String> usedSlots = new HashSet<>();
            for (Map.Entry<String, JsonNode> entry : runs.entrySet()) {
                String runId = entry.getKey();
                JsonNode identity = entry.getValue().path("runIdentity");
                Path runDirectory = evidenceRoot.resolve(batchId).resolve(runId);
                JsonNode manifest = json.readTree(runDirectory.resolve("evidence-manifest.json").toFile());
                String slotId = requiredText(manifest, "slotId");
                JsonNode slot = slots.get(slotId);
                if (slot == null || !usedSlots.add(slotId)) invalid("slot coverage");
                validateManifest(manifest, slot, identity, batchId, runId,
                        expectedProfileId, expectedProfileDigest, expectedSnapshotDigest);
                validateArtifacts(runDirectory, manifest.path("artifacts"));
                recomputer.verify(runDirectory, manifest);
                results.add(new ReleaseRun(runId, slotId, requiredText(manifest, "scenarioId"),
                        requiredText(manifest, "datasetRunId"), runDirectory));
            }
            if (!usedSlots.equals(slots.keySet())) invalid("incomplete slot coverage");
            return new ReleaseBatch(batchId, expectedProfileId, expectedSnapshotDigest, results);
        } catch (IOException exception) {
            throw new IllegalStateException("RELEASE_BATCH_READ_FAILED", exception);
        }
    }

    private Map<String, JsonNode> readSlots(JsonNode values, String snapshotDigest) {
        if (!values.isArray() || values.size() != 15) invalid("slots");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        Map<String, Set<Integer>> ordinals = new HashMap<>();
        for (JsonNode slot : values) {
            String slotId = requiredText(slot, "slotId");
            String scenario = requiredText(slot, "scenarioId");
            int ordinal = slot.path("ordinal").asInt(-1);
            if (!SCENARIOS.contains(scenario) || ordinal < 1 || ordinal > 5
                    || !"1.0.0".equals(requiredText(slot, "scenarioVersion"))
                    || !"RELEASE_QUALITY".equals(requiredText(slot, "runPurpose"))
                    || !snapshotDigest.equals(requiredText(slot, "snapshotDigest"))
                    || result.putIfAbsent(slotId, slot) != null
                    || !ordinals.computeIfAbsent(scenario, ignored -> new HashSet<>()).add(ordinal)) {
                invalid("slot identity");
            }
        }
        if (!ordinals.keySet().equals(SCENARIOS)
                || ordinals.values().stream().anyMatch(valuesForScenario -> valuesForScenario.size() != 5)) {
            invalid("3x5 scenario coverage");
        }
        return result;
    }

    private Map<String, JsonNode> readLedger(
            Path ledgerPath, String batchId, String snapshotDigest) throws IOException {
        Map<String, JsonNode> runs = new LinkedHashMap<>();
        Set<String> identities = new HashSet<>();
        for (String line : Files.readAllLines(ledgerPath)) {
            if (line.isBlank()) continue;
            JsonNode entry = json.readTree(line);
            if (!batchId.equals(entry.path("releaseBatchId").asText())
                    || !"RELEASE_QUALITY".equals(entry.path("runPurpose").asText())) continue;
            if (!"PASSED".equals(entry.path("status").asText())
                    || !snapshotDigest.equals(entry.path("environmentDigest").asText())) {
                invalid("ledger status or snapshot");
            }
            JsonNode identity = entry.path("runIdentity");
            String runId = requiredText(identity, "runId");
            if (!runId.equals(requiredText(identity, "a2aContextId"))
                    || runs.putIfAbsent(runId, entry) != null) invalid("duplicate run identity");
            for (String field : List.of("incidentId", "runId", "a2aContextId", "supervisorSessionId")) {
                if (!identities.add(field + ":" + requiredText(identity, field))) invalid("reused run identity");
            }
            validateTasks(identity.path("a2aTasks"), identities);
            requiredText(identity, "datasetRunId");
        }
        return runs;
    }

    private void validateTasks(JsonNode tasks, Set<String> identities) {
        if (!tasks.isArray() || tasks.size() != 5) invalid("professional task count");
        Set<String> agents = new HashSet<>();
        for (JsonNode task : tasks) {
            String agent = requiredText(task, "agentId");
            if (!PROFESSIONAL_AGENTS.contains(agent) || !agents.add(agent)) invalid("professional agent set");
            for (String field : List.of("a2aTaskId", "messageId", "agentScopeSessionId")) {
                if (!identities.add(field + ":" + requiredText(task, field))) invalid("reused task identity");
            }
        }
        if (!agents.equals(PROFESSIONAL_AGENTS)) invalid("professional agent coverage");
    }

    private void validateManifest(
            JsonNode manifest, JsonNode slot, JsonNode identity, String batchId, String runId,
            String profileId, String profileDigest, String snapshotDigest) {
        boolean valid = "RELEASE_QUALITY".equals(manifest.path("runPurpose").asText())
                && batchId.equals(manifest.path("releaseBatchId").asText())
                && runId.equals(manifest.path("runId").asText())
                && requiredText(slot, "slotId").equals(manifest.path("slotId").asText())
                && requiredText(slot, "scenarioId").equals(manifest.path("scenarioId").asText())
                && requiredText(identity, "datasetRunId").equals(manifest.path("datasetRunId").asText())
                && profileId.equals(manifest.path("evaluationProfileId").asText())
                && profileDigest.equals(manifest.path("evaluationProfileDigest").asText())
                && snapshotDigest.equals(manifest.path("snapshotDigest").asText())
                && manifest.path("datasetValid").asBoolean(false)
                && executionIntegrityValid(manifest.path("executionIntegrity"));
        if (!valid) invalid("evidence identity or dataset validity");
    }

    private static boolean executionIntegrityValid(JsonNode integrity) {
        return integrity.isObject() && integrity.size() == 7
                && !integrity.path("mockUsed").asBoolean(true)
                && !integrity.path("hiddenFallbackUsed").asBoolean(true)
                && !integrity.path("vectorOnly").asBoolean(true)
                && !integrity.path("keywordFallbackUsed").asBoolean(true)
                && !integrity.path("fixedResultUsed").asBoolean(true)
                && integrity.path("rerankRequired").asBoolean(false)
                && integrity.path("rerankExecuted").asBoolean(false);
    }

    private void validateArtifacts(Path runDirectory, JsonNode artifacts) throws IOException {
        if (!artifacts.isArray() || artifacts.size() != EVIDENCE_TYPES.size()) invalid("evidence count");
        Set<String> types = new HashSet<>();
        for (JsonNode artifact : artifacts) {
            String type = requiredText(artifact, "evidenceType");
            String uri = requiredText(artifact, "uri");
            if (!EVIDENCE_TYPES.contains(type) || !types.add(type) || uri.contains("\\")) {
                invalid("evidence type or URI");
            }
            Path path = runDirectory.resolve(uri.substring(uri.lastIndexOf('/') + 1));
            byte[] content = Files.readAllBytes(path);
            if (artifact.path("size").asLong(-1) != content.length
                    || !sha256(content).equals(requiredText(artifact, "sha256"))) {
                invalid("evidence digest");
            }
        }
        if (!types.equals(EVIDENCE_TYPES)) invalid("required evidence set");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) invalid(field);
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void invalid(String detail) {
        throw new IllegalStateException("RELEASE_BATCH_INVALID: " + detail);
    }

    @FunctionalInterface
    public interface RunRecomputer {
        void verify(Path runEvidenceDirectory, JsonNode evidenceManifest);
    }

    public record ReleaseRun(
            String runId, String slotId, String scenarioId, String datasetRunId, Path evidenceDirectory) { }

    public record ReleaseBatch(
            String releaseBatchId, String evaluationProfileId, String snapshotDigest, List<ReleaseRun> runs) {
        public ReleaseBatch { runs = List.copyOf(runs); }
    }
}
