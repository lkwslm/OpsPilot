package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Independent Evaluation container entry point; it never starts an Agent runtime. */
public final class EvaluationProcess {
    private EvaluationProcess() { }

    public static void main(String[] args) throws Exception {
        String profilePath = requiredEnvironment("OPSPILOT_EVALUATION_PROFILE");
        var json = new ObjectMapper().findAndRegisterModules();
        var loader = new EvaluationProfileLoader(json);
        var profile = loader.load(Path.of(profilePath));
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
                    "usage: EvaluationProcess serve | evaluate-run <run-id> <ground-truth-json>");
        }
        System.out.printf("evaluation-ready profile=%s version=%s snapshot=%s%n",
                profile.profileId(), profile.profileVersion(), profile.snapshotSha256());
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
