package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseBatchReaderTest {
    private static final String BATCH = "phase8-release-01";
    private static final String PROFILE = "mvp-v2";
    private static final String PROFILE_DIGEST = "a".repeat(64);
    private static final String SNAPSHOT = "b".repeat(64);
    private static final List<String> SCENARIOS = List.of(
            "database-pool-exhausted-order",
            "dependency-latency-inventory",
            "service-instance-stopped-inventory");
    private static final List<String> AGENTS = List.of(
            "evidence-collector", "code-analysis", "knowledge", "diagnosis", "remediation");
    private static final List<String> EVIDENCE_TYPES = List.of(
            "rca-json", "rca-markdown", "evaluation", "provider-calls", "tool-calls",
            "a2a-calls", "usage-ledger", "state-events", "citations", "artifact-index");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsFifteenRunsAndRecomputesEveryEvaluation(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        AtomicInteger recomputed = new AtomicInteger();

        var batch = new ReleaseBatchReader(json).read(
                fixture.plan(), fixture.ledger(), fixture.evidenceRoot(),
                PROFILE, PROFILE_DIGEST, SNAPSHOT,
                (directory, manifest) -> {
                    try {
                        ObjectNode evaluation = (ObjectNode) json.readTree(
                                directory.resolve("evaluation.json").toFile());
                        assertEquals(manifest.path("runId").asText(), evaluation.path("runId").asText());
                        recomputed.incrementAndGet();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });

        assertEquals(15, batch.runs().size());
        assertEquals(15, recomputed.get());
        assertEquals(3, batch.runs().stream().map(ReleaseBatchReader.ReleaseRun::scenarioId).distinct().count());
    }

    @Test
    void rejectsInvalidDatasetOrProfileDrift(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Path manifestPath = fixture.evidenceRoot().resolve(BATCH).resolve("run-01")
                .resolve("evidence-manifest.json");
        ObjectNode manifest = (ObjectNode) json.readTree(manifestPath.toFile());
        manifest.put("datasetValid", false);
        json.writeValue(manifestPath.toFile(), manifest);

        assertThrows(IllegalStateException.class, () -> new ReleaseBatchReader(json).read(
                fixture.plan(), fixture.ledger(), fixture.evidenceRoot(),
                PROFILE, PROFILE_DIGEST, SNAPSHOT, (directory, evidence) -> { }));
    }

    @Test
    void rejectsTamperedEvidenceBeforeRecompute(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Files.writeString(
                fixture.evidenceRoot().resolve(BATCH).resolve("run-01").resolve("evaluation.json"),
                "tampered", StandardCharsets.UTF_8);
        AtomicInteger recomputed = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> new ReleaseBatchReader(json).read(
                fixture.plan(), fixture.ledger(), fixture.evidenceRoot(),
                PROFILE, PROFILE_DIGEST, SNAPSHOT,
                (directory, evidence) -> recomputed.incrementAndGet()));
        assertEquals(0, recomputed.get());
    }

    @Test
    void rejectsNonQualityPurposeFallbackOrProfileDrift(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        Path manifestPath = fixture.evidenceRoot().resolve(BATCH).resolve("run-01")
                .resolve("evidence-manifest.json");
        ObjectNode original = (ObjectNode) json.readTree(manifestPath.toFile());
        for (String purpose : List.of("BASELINE_ONLY", "EMPTY_OUTCOME", "FAILURE_INJECTION",
                "RECOVERY", "SECURITY", "PERFORMANCE")) {
            ObjectNode changed = original.deepCopy();
            changed.put("runPurpose", purpose);
            json.writeValue(manifestPath.toFile(), changed);
            assertInvalid(fixture);
        }
        for (String field : List.of("mockUsed", "hiddenFallbackUsed", "vectorOnly",
                "keywordFallbackUsed", "fixedResultUsed")) {
            ObjectNode changed = original.deepCopy();
            ((ObjectNode) changed.path("executionIntegrity")).put(field, true);
            json.writeValue(manifestPath.toFile(), changed);
            assertInvalid(fixture);
        }
        ObjectNode skippedRerank = original.deepCopy();
        ((ObjectNode) skippedRerank.path("executionIntegrity")).put("rerankExecuted", false);
        json.writeValue(manifestPath.toFile(), skippedRerank);
        assertInvalid(fixture);

        ObjectNode profileDrift = original.deepCopy();
        profileDrift.put("evaluationProfileDigest", "f".repeat(64));
        json.writeValue(manifestPath.toFile(), profileDrift);
        assertInvalid(fixture);
    }

    private void assertInvalid(Fixture fixture) {
        assertThrows(IllegalStateException.class, () -> new ReleaseBatchReader(json).read(
                fixture.plan(), fixture.ledger(), fixture.evidenceRoot(),
                PROFILE, PROFILE_DIGEST, SNAPSHOT, (directory, evidence) -> { }));
    }

    private Fixture fixture(Path root) throws Exception {
        Path planPath = root.resolve("quality-plan.json");
        Path ledgerPath = root.resolve("runs.jsonl");
        Path evidenceRoot = root.resolve("evidence");
        ObjectNode plan = json.createObjectNode();
        plan.put("releaseBatchId", BATCH);
        plan.put("runPurpose", "RELEASE_QUALITY");
        plan.put("snapshotDigest", SNAPSHOT);
        plan.put("evaluationProfileId", PROFILE);
        plan.put("runsPerScenario", 5);
        plan.put("totalSlots", 15);
        ArrayNode slots = plan.putArray("slots");
        StringBuilder ledger = new StringBuilder();

        int runNumber = 0;
        for (String scenario : SCENARIOS) {
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                runNumber++;
                String slotId = "%s:%02d".formatted(scenario, ordinal);
                String runId = "run-%02d".formatted(runNumber);
                String datasetRunId = "dataset-%02d".formatted(runNumber);
                slots.addObject()
                        .put("slotId", slotId)
                        .put("scenarioId", scenario)
                        .put("scenarioVersion", "1.0.0")
                        .put("ordinal", ordinal)
                        .put("runPurpose", "RELEASE_QUALITY")
                        .put("snapshotDigest", SNAPSHOT)
                        .put("status", "PLANNED");
                ledger.append(json.writeValueAsString(ledgerEntry(runNumber, runId, datasetRunId))).append('\n');
                writeEvidence(evidenceRoot, slotId, scenario, runId, datasetRunId);
            }
        }
        json.writeValue(planPath.toFile(), plan);
        Files.writeString(ledgerPath, ledger, StandardCharsets.UTF_8);
        return new Fixture(planPath, ledgerPath, evidenceRoot);
    }

    private ObjectNode ledgerEntry(int number, String runId, String datasetRunId) {
        ObjectNode entry = json.createObjectNode();
        entry.put("releaseBatchId", BATCH);
        entry.put("runPurpose", "RELEASE_QUALITY");
        entry.put("status", "PASSED");
        entry.put("environmentDigest", SNAPSHOT);
        ObjectNode identity = entry.putObject("runIdentity");
        identity.put("incidentId", "incident-%02d".formatted(number));
        identity.put("runId", runId);
        identity.put("datasetRunId", datasetRunId);
        identity.put("a2aContextId", runId);
        identity.put("supervisorSessionId", "supervisor:%02d".formatted(number));
        ArrayNode tasks = identity.putArray("a2aTasks");
        for (String agent : AGENTS) {
            String taskId = "task-%02d-%s".formatted(number, agent);
            tasks.addObject()
                    .put("agentId", agent)
                    .put("a2aTaskId", taskId)
                    .put("messageId", runId + ":" + agent + ":1")
                    .put("agentScopeSessionId", agent + ":" + taskId);
        }
        identity.put("attempt", 1);
        return entry;
    }

    private void writeEvidence(
            Path evidenceRoot, String slotId, String scenario, String runId, String datasetRunId)
            throws Exception {
        Path directory = evidenceRoot.resolve(BATCH).resolve(runId);
        Files.createDirectories(directory);
        ObjectNode manifest = json.createObjectNode();
        manifest.put("releaseBatchId", BATCH);
        manifest.put("runPurpose", "RELEASE_QUALITY");
        manifest.put("runId", runId);
        manifest.put("slotId", slotId);
        manifest.put("scenarioId", scenario);
        manifest.put("datasetRunId", datasetRunId);
        manifest.put("evaluationProfileId", PROFILE);
        manifest.put("evaluationProfileDigest", PROFILE_DIGEST);
        manifest.put("snapshotDigest", SNAPSHOT);
        manifest.put("datasetValid", true);
        ObjectNode integrity = manifest.putObject("executionIntegrity");
        integrity.put("mockUsed", false);
        integrity.put("hiddenFallbackUsed", false);
        integrity.put("vectorOnly", false);
        integrity.put("keywordFallbackUsed", false);
        integrity.put("fixedResultUsed", false);
        integrity.put("rerankRequired", true);
        integrity.put("rerankExecuted", true);
        ArrayNode artifacts = manifest.putArray("artifacts");
        for (String type : EVIDENCE_TYPES) {
            String suffix = type.equals("rca-markdown") ? ".md" : ".json";
            String filename = type + suffix;
            byte[] content = type.equals("evaluation")
                    ? json.writeValueAsBytes(json.createObjectNode().put("runId", runId))
                    : (type + ":" + runId).getBytes(StandardCharsets.UTF_8);
            Files.write(directory.resolve(filename), content);
            artifacts.addObject()
                    .put("evidenceType", type)
                    .put("uri", "artifact://phase8/" + BATCH + "/" + runId + "/" + filename)
                    .put("size", content.length)
                    .put("sha256", sha256(content));
        }
        json.writeValue(directory.resolve("evidence-manifest.json").toFile(), manifest);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Fixture(Path plan, Path ledger, Path evidenceRoot) { }
}
