package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

/** Independent lease-based deterministic evaluation worker. */
final class EvaluationWorker {
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final EvaluationProfileLoader loader;
    private final EvaluationModels.EvaluationProfile profile;
    private final Path groundTruthRoot;
    private final String workerId;

    EvaluationWorker(DataSource dataSource, ObjectMapper json, EvaluationProfileLoader loader,
            EvaluationModels.EvaluationProfile profile, Path groundTruthRoot, String workerId) {
        this.dataSource = dataSource;
        this.json = json;
        this.loader = loader;
        this.profile = profile;
        this.groundTruthRoot = groundTruthRoot.toAbsolutePath().normalize();
        this.workerId = workerId;
    }

    void serve() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            ClaimedTask task = claim();
            if (task == null) {
                Thread.sleep(500);
                continue;
            }
            try {
                evaluate(task);
                complete(task.taskId());
            } catch (Exception failure) {
                System.err.printf("EVALUATION_FAILURE taskId=%s type=%s%n",
                        task.taskId(), failure.getClass().getSimpleName());
                fail(task.taskId());
            }
        }
    }

    private void evaluate(ClaimedTask task) throws Exception {
        String relative = task.payload().path("groundTruthRelativePath").asText();
        if (!relative.matches("[0-9a-f-]{36}/ground-truth\\.json")) {
            throw new IllegalStateException("EVALUATION_GROUND_TRUTH_PATH_INVALID");
        }
        Path truthPath = groundTruthRoot.resolve(relative).normalize();
        if (!truthPath.startsWith(groundTruthRoot)) {
            throw new IllegalStateException("EVALUATION_GROUND_TRUTH_PATH_ESCAPE");
        }
        var truth = loader.loadGroundTruth(truthPath);
        String expectedScenario = task.payload().path("scenarioId").asText();
        if (!truth.scenarioId().equals(expectedScenario)) {
            throw new IllegalStateException("EVALUATION_SCENARIO_MISMATCH");
        }
        var input = new EvaluationRunReader(dataSource, json).read(task.runId(), truth.scenarioId());
        var engine = new DeterministicEvaluationEngine();
        var result = engine.evaluate(input, truth, profile);
        new EvaluationVerifier(engine, json).verify(input, truth, profile, result);
        var rendered = new EvaluationReportRenderer(json).render(result);
        new PostgresEvaluationResultRepository(dataSource).save(task.runId(), profile, rendered);
    }

    private ClaimedTask claim() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT task_id,run_id,payload_json::text FROM opspilot.claim_evaluation_task(?,120)")) {
            statement.setString(1, workerId);
            try (var result = statement.executeQuery()) {
                return result.next() ? new ClaimedTask(result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class), json.readTree(result.getString(3))) : null;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("EVALUATION_TASK_CLAIM_FAILED", failure);
        }
    }

    private void complete(UUID taskId) {
        transition("SELECT opspilot.complete_evaluation_task(?,?)", taskId);
    }

    private void fail(UUID taskId) {
        transition("SELECT opspilot.fail_evaluation_task(?,?,5)", taskId);
    }

    private void transition(String sql, UUID taskId) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, taskId);
            statement.setString(2, workerId);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("EVALUATION_TASK_TRANSITION_CONFLICT");
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("EVALUATION_TASK_TRANSITION_FAILED", failure);
        }
    }

    private record ClaimedTask(UUID taskId, UUID runId, JsonNode payload) { }
}
