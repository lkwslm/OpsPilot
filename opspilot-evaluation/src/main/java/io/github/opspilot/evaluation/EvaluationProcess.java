package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opspilot.evaluation.ReleaseQualityAggregator.ReleaseEvaluationRun;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Independent Evaluation container entry point; it never starts an Agent runtime. */
public final class EvaluationProcess {
    private EvaluationProcess() { }

    public static void main(String[] args) throws Exception {
        String profilePath = requiredEnvironment("OPSPILOT_EVALUATION_PROFILE");
        var json = new ObjectMapper().findAndRegisterModules();
        var loader = new EvaluationProfileLoader(json);
        var profile = loader.load(Path.of(profilePath));
        if (args.length == 6 && "aggregate-release".equals(args[0])) {
            aggregateRelease(args, Path.of(profilePath), json, loader, profile);
            return;
        }
        if (args.length == 3 && "evaluate-run".equals(args[0])) {
            UUID runId = UUID.fromString(args[1]);
            var truth = loader.loadGroundTruth(Path.of(args[2]));
            PGSimpleDataSource dataSource = dataSource();
            var input = new EvaluationRunReader(dataSource, json).read(runId, truth.scenarioId());
            var engine = new DeterministicEvaluationEngine();
            var result = engine.evaluate(input, truth, profile);
            new EvaluationVerifier(engine, json).verify(input, truth, profile, result);
            var rendered = new EvaluationReportRenderer(json).render(result);
            new PostgresEvaluationResultRepository(dataSource).save(runId, profile, rendered);
            System.out.println(rendered.jsonReport());
            return;
        }
        if (args.length != 1 || !"serve".equals(args[0])) {
            throw new IllegalArgumentException(
                    "usage: EvaluationProcess serve | evaluate-run <run-id> <ground-truth-json>"
                            + " | aggregate-release <plan> <ledger> <evidence-root>"
                            + " <snapshot-digest> <output-json>");
        }
        System.out.printf("evaluation-ready profile=%s version=%s snapshot=%s%n",
                profile.profileId(), profile.profileVersion(), profile.snapshotSha256());
        new EvaluationWorker(dataSource(), json, loader, profile,
                Path.of(requiredEnvironment("GROUND_TRUTH_DIR")),
                System.getenv().getOrDefault("EVALUATION_WORKER_ID", "evaluation:primary"))
                .serve();
    }

    private static void aggregateRelease(
            String[] args,
            Path profilePath,
            ObjectMapper json,
            EvaluationProfileLoader loader,
            EvaluationModels.EvaluationProfile profile) throws Exception {
        Path groundTruthRoot = Path.of(requiredEnvironment("GROUND_TRUTH_DIR"));
        PGSimpleDataSource dataSource = dataSource();
        Map<String, ReleaseEvaluationRun> verifiedRuns = new LinkedHashMap<>();
        var engine = new DeterministicEvaluationEngine();
        var verifier = new EvaluationVerifier(engine, json);
        var renderer = new EvaluationReportRenderer(json);

        var batch = new ReleaseBatchReader(json).read(
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                profile.profileId(), sha256(Files.readAllBytes(profilePath)), args[4],
                (directory, manifest) -> {
                    try {
                        UUID runId = UUID.fromString(manifest.path("runId").asText());
                        String scenarioId = manifest.path("scenarioId").asText();
                        Path truthPath = groundTruthRoot.resolve(
                                manifest.path("datasetRunId").asText()).resolve("ground-truth.json");
                        var truth = loader.loadGroundTruth(truthPath);
                        var input = new EvaluationRunReader(dataSource, json).read(runId, scenarioId);
                        var recomputed = engine.evaluate(input, truth, profile);
                        verifier.verify(input, truth, profile, recomputed);

                        ObjectNode sealed = (ObjectNode) json.readTree(
                                directory.resolve("evaluation.json").toFile());
                        var stored = json.treeToValue(
                                sealed.path("report"), EvaluationModels.EvaluationResult.class);
                        String recomputedDigest = renderer.render(recomputed).resultDigest();
                        if (!"COMPLETED".equals(sealed.path("status").asText())
                                || !profile.profileId().equals(sealed.path("profileId").asText())
                                || !profile.snapshotSha256().equals(
                                        sealed.path("profileSnapshotSha256").asText())
                                || !scenarioId.equals(sealed.path("scenarioId").asText())
                                || !recomputed.equals(stored)
                                || !recomputedDigest.equals(sealed.path("resultDigest").asText())) {
                            throw new IllegalStateException(
                                    "RELEASE_EVALUATION_RECOMPUTE_MISMATCH: " + runId);
                        }
                        String outcome = json.readTree(directory.resolve("rca-json.json").toFile())
                                .path("outcome").asText();
                        verifiedRuns.put(runId.toString(), new ReleaseEvaluationRun(outcome, recomputed));
                    } catch (Exception exception) {
                        throw new IllegalStateException("RELEASE_EVALUATION_RECOMPUTE_FAILED", exception);
                    }
                });

        var runs = batch.runs().stream().map(run -> verifiedRuns.get(run.runId())).toList();
        if (runs.stream().anyMatch(value -> value == null)) {
            throw new IllegalStateException("RELEASE_EVALUATION_RECOMPUTE_INCOMPLETE");
        }
        var aggregate = new ReleaseQualityAggregator().aggregate(runs);
        var gate = new ReleaseQualityGateEvaluator().evaluate(aggregate, batch.runs().size(), 15);
        ObjectNode report = json.createObjectNode();
        report.put("schemaVersion", "1.0.0");
        report.put("releaseBatchId", batch.releaseBatchId());
        report.put("status", gate.status());
        report.put("evaluationProfileId", profile.profileId());
        report.put("evaluationProfileDigest", sha256(Files.readAllBytes(profilePath)));
        report.put("snapshotDigest", batch.snapshotDigest());
        report.put("totalRuns", batch.runs().size());
        report.put("generatedAt", Instant.now().toString());
        report.set("aggregate", json.valueToTree(aggregate));
        report.set("gate", json.valueToTree(gate));
        writeNewJson(Path.of(args[5]), json.writeValueAsBytes(report));
        System.out.println(json.writeValueAsString(report));
    }

    private static void writeNewJson(Path output, byte[] content) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        if (Files.exists(output)) {
            throw new IllegalStateException("RELEASE_GATE_OUTPUT_EXISTS: " + output);
        }
        Path temporary = parent.resolve("." + output.getFileName() + ".tmp-" + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, output.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static PGSimpleDataSource dataSource() throws Exception {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(requiredEnvironment("JDBC_URL"));
        dataSource.setUser(requiredEnvironment("DB_USERNAME"));
        dataSource.setPassword(Files.readString(
                Path.of(requiredEnvironment("DB_PASSWORD_FILE")), StandardCharsets.UTF_8).strip());
        return dataSource;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("EVALUATION_ENV_MISSING:" + name);
        return value;
    }
}
