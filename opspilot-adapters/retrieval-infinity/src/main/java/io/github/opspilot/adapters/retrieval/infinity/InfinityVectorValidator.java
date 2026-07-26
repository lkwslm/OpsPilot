package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;

import java.util.List;
import java.util.Objects;

/** Fails closed on response order, shape, numbers, and locked embedding identity. */
public final class InfinityVectorValidator {
    private static final double UNIT_NORM_TOLERANCE = 0.02;

    public List<float[]> validate(
            InfinityEmbeddingDtos.EmbeddingResponse response,
            int expectedCount,
            InfinityEmbeddingConfiguration expected) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(expected, "expected");
        if (!expected.servedModel().equals(response.model())) {
            throw new VectorContractException("EMBEDDING_MODEL_MISMATCH");
        }
        if (response.data() == null || response.data().size() != expectedCount) {
            throw new VectorContractException("EMBEDDING_COUNT_MISMATCH");
        }
        java.util.ArrayList<float[]> vectors = new java.util.ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            InfinityEmbeddingDtos.EmbeddingData item = response.data().get(index);
            if (item == null || item.index() != index) {
                throw new VectorContractException("EMBEDDING_ORDER_UNPROVEN");
            }
            vectors.add(validateVector(item.embedding(), expected));
        }
        return List.copyOf(vectors);
    }

    public float[] validateVector(List<Double> values, InfinityEmbeddingConfiguration expected) {
        if (values == null || values.isEmpty()) {
            throw new VectorContractException("EMBEDDING_VECTOR_EMPTY");
        }
        if (values.size() != expected.dimension()) {
            throw new VectorContractException("EMBEDDING_DIMENSION_MISMATCH");
        }
        float[] vector = new float[values.size()];
        double normSquared = 0;
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new VectorContractException("EMBEDDING_NON_FINITE");
            }
            vector[index] = value.floatValue();
            if (!Float.isFinite(vector[index])) {
                throw new VectorContractException("EMBEDDING_NON_FINITE");
            }
            normSquared += value * value;
        }
        if (expected.distanceMetric() == DistanceMetric.COSINE && normSquared == 0) {
            throw new VectorContractException("EMBEDDING_ZERO_NORM");
        }
        if (expected.normalization() == Normalization.L2_UNIT
                && Math.abs(Math.sqrt(normSquared) - 1.0) > UNIT_NORM_TOLERANCE) {
            throw new VectorContractException("EMBEDDING_NORMALIZATION_MISMATCH");
        }
        return vector;
    }

    public float[] validateVector(float[] values, InfinityEmbeddingConfiguration expected) {
        if (values == null) throw new VectorContractException("EMBEDDING_VECTOR_EMPTY");
        java.util.ArrayList<Double> boxed = new java.util.ArrayList<>(values.length);
        for (float value : values) boxed.add((double) value);
        return validateVector(boxed, expected);
    }

    public static final class VectorContractException extends IllegalArgumentException {
        public VectorContractException(String code) { super(code); }
    }
}
