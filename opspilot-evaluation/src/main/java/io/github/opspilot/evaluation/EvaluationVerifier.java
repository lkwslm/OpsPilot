package io.github.opspilot.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationInput;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationProfile;
import io.github.opspilot.evaluation.EvaluationModels.EvaluationResult;
import io.github.opspilot.evaluation.EvaluationModels.GroundTruth;

/** Independently recomputes a persisted result from raw run facts. */
public final class EvaluationVerifier {
    private final DeterministicEvaluationEngine engine;
    private final ObjectMapper json;
    public EvaluationVerifier(DeterministicEvaluationEngine engine, ObjectMapper json) {
        this.engine = engine;
        this.json = json;
    }

    public void verify(EvaluationInput rawFacts, GroundTruth truth,
                       EvaluationProfile profile, EvaluationResult persisted) {
        EvaluationResult recomputed = engine.evaluate(rawFacts, truth, profile);
        try {
            if (!json.valueToTree(recomputed).equals(json.valueToTree(persisted))) {
                throw new IllegalStateException("EVALUATION_RECOMPUTE_MISMATCH");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("EVALUATION_RECOMPUTE_FAILED", exception);
        }
    }
}
