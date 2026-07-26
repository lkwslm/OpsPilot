package io.github.opspilot.adapters.knowledge.pgvector;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact pgvector retrieval with a closed distance-operator allowlist. */
public final class PgvectorKnowledgeRepository {
    private static final int EXACT_SCAN_LIMIT = 50_000;
    private static final Set<String> FILTER_KEYS = Set.of(
            "language", "service", "documentType", "tag", "relation");
    private static final Map<DistanceMetric, String> OPERATORS = operators();

    private final DataSource dataSource;

    public PgvectorKnowledgeRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void insertEmbedding(
            UUID chunkId, UUID modelRevisionId, int dimension, DistanceMetric metric,
            float[] vector, String contentSha256) {
        validateVector(vector, dimension, metric);
        String sql = """
                INSERT INTO opspilot.knowledge_embedding
                    (chunk_id, model_revision_id, embedding_dimension, embedding, content_sha256)
                SELECT ?, revision.model_revision_id, ?, ?::vector, ?
                FROM opspilot.model_revision revision
                WHERE revision.model_revision_id = ?
                  AND revision.embedding_dimension = ?
                  AND revision.distance_metric = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, chunkId);
            statement.setInt(2, dimension);
            statement.setString(3, vectorLiteral(vector));
            statement.setString(4, contentSha256);
            statement.setObject(5, modelRevisionId);
            statement.setInt(6, dimension);
            statement.setString(7, metric.name());
            if (statement.executeUpdate() != 1) {
                throw new VectorContractException("VECTOR_REVISION_MISMATCH");
            }
        } catch (SQLException exception) {
            throw new VectorContractException("VECTOR_WRITE_REJECTED", exception);
        }
    }

    public List<SearchHit> exactTopK(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        validateVector(request.queryVector(), request.dimension(), request.metric());
        if (request.topK() < 1 || request.topK() > 1_000) {
            throw new IllegalArgumentException("topK must be between 1 and 1000");
        }
        if (!FILTER_KEYS.containsAll(request.metadataFilters().keySet())) {
            throw new IllegalArgumentException("VECTOR_FILTER_NOT_ALLOWED");
        }
        validateQueryRevision(request);
        ensureExactScanBaseline(request.collectionId());

        StringBuilder sql = new StringBuilder("""
                SELECT chunk.chunk_id,
                       chunk.content_artifact_id,
                       embedding.embedding """)
                .append(OPERATORS.get(request.metric()))
                .append(" ?::vector AS distance, document.document_id, version.document_version_id,\n")
                .append("       chunk.content_sha256, chunk.source_location\n")
                .append("""
                        FROM opspilot.knowledge_embedding embedding
                        JOIN opspilot.model_revision revision
                          ON revision.model_revision_id = embedding.model_revision_id
                        JOIN opspilot.knowledge_chunk chunk ON chunk.chunk_id = embedding.chunk_id
                        JOIN opspilot.knowledge_document_version version
                          ON version.document_version_id = chunk.document_version_id
                        JOIN opspilot.knowledge_document document ON document.document_id = version.document_id
                        JOIN opspilot.knowledge_collection collection
                          ON collection.collection_id = document.collection_id
                        WHERE collection.collection_id = ?
                          AND collection.active_model_revision_id = ?
                          AND embedding.model_revision_id = ?
                          AND revision.embedding_dimension = ?
                          AND revision.distance_metric = ?
                          AND version.status = 'ACTIVE'
                          AND chunk.searchable
                          AND chunk.deleted_at IS NULL
                          AND document.status = 'ACTIVE'
                          AND document.deleted_at IS NULL
                        """);
        if (request.knowledgeRevisionId() != null) {
            sql.append(" AND version.knowledge_revision_id = ?\n")
                    .append(" AND EXISTS (SELECT 1 FROM opspilot.knowledge_revision knowledge_revision\n")
                    .append("             WHERE knowledge_revision.knowledge_revision_id = version.knowledge_revision_id\n")
                    .append("               AND knowledge_revision.status IN ('ACTIVE','RETAINED'))\n");
        }
        if (!request.aclPrincipals().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(version.acl_json -> 'principals') principal\n")
                    .append("             WHERE principal.value = ANY (?::text[]))\n");
        }
        request.metadataFilters().forEach((ignored, value) ->
                sql.append(" AND chunk.metadata_json ->> ? = ?\n"));
        sql.append(" ORDER BY embedding.embedding ")
                .append(OPERATORS.get(request.metric()))
                .append(" ?::vector, chunk.chunk_id LIMIT ?");

        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql.toString())) {
            String vector = vectorLiteral(request.queryVector());
            int index = 1;
            statement.setString(index++, vector);
            statement.setObject(index++, request.collectionId());
            statement.setObject(index++, request.modelRevisionId());
            statement.setObject(index++, request.modelRevisionId());
            statement.setInt(index++, request.dimension());
            statement.setString(index++, request.metric().name());
            if (request.knowledgeRevisionId() != null) {
                statement.setObject(index++, request.knowledgeRevisionId());
            }
            if (!request.aclPrincipals().isEmpty()) {
                statement.setArray(index++, connection.createArrayOf(
                        "text", request.aclPrincipals().toArray(String[]::new)));
            }
            for (var filter : request.metadataFilters().entrySet()) {
                statement.setString(index++, filter.getKey());
                statement.setString(index++, filter.getValue());
            }
            statement.setString(index++, vector);
            statement.setInt(index, request.topK());
            List<SearchHit> hits = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    hits.add(new SearchHit(
                            result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getDouble(3),
                            result.getObject(4, UUID.class), result.getObject(5, UUID.class),
                            result.getString(6), result.getString(7)));
                }
            }
            return List.copyOf(hits);
        } catch (SQLException exception) {
            throw new VectorContractException("VECTOR_QUERY_REJECTED", exception);
        }
    }

    private void ensureExactScanBaseline(UUID collectionId) {
        String sql = """
                SELECT count(*)
                FROM opspilot.knowledge_chunk chunk
                JOIN opspilot.knowledge_document_version version
                  ON version.document_version_id = chunk.document_version_id
                JOIN opspilot.knowledge_document document ON document.document_id = version.document_id
                WHERE document.collection_id = ? AND version.status = 'ACTIVE'
                  AND chunk.searchable AND chunk.deleted_at IS NULL AND document.deleted_at IS NULL
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, collectionId);
            try (var result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) >= EXACT_SCAN_LIMIT) {
                    throw new VectorContractException("EXACT_SCAN_BASELINE_EXCEEDED");
                }
            }
        } catch (SQLException exception) {
            throw new VectorContractException("VECTOR_BASELINE_CHECK_FAILED", exception);
        }
    }

    private void validateQueryRevision(SearchRequest request) {
        String sql = """
                SELECT 1
                FROM opspilot.knowledge_collection collection
                JOIN opspilot.model_revision revision
                  ON revision.model_revision_id = collection.active_model_revision_id
                WHERE collection.collection_id = ?
                  AND revision.model_revision_id = ?
                  AND revision.embedding_dimension = ?
                  AND revision.distance_metric = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, request.collectionId());
            statement.setObject(2, request.modelRevisionId());
            statement.setInt(3, request.dimension());
            statement.setString(4, request.metric().name());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new VectorContractException("VECTOR_QUERY_REVISION_MISMATCH");
                }
            }
        } catch (SQLException exception) {
            throw new VectorContractException("VECTOR_QUERY_REVISION_CHECK_FAILED", exception);
        }
    }

    static void validateVector(float[] vector, int expectedDimension, DistanceMetric metric) {
        Objects.requireNonNull(vector, "vector");
        Objects.requireNonNull(metric, "metric");
        if (expectedDimension < 1 || vector.length != expectedDimension) {
            throw new VectorContractException("VECTOR_DIMENSION_MISMATCH");
        }
        double normSquared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new VectorContractException("VECTOR_NON_FINITE");
            }
            normSquared += value * value;
        }
        if (metric == DistanceMetric.COSINE && normSquared == 0) {
            throw new VectorContractException("VECTOR_ZERO_NORM");
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    private static Map<DistanceMetric, String> operators() {
        Map<DistanceMetric, String> values = new EnumMap<>(DistanceMetric.class);
        values.put(DistanceMetric.COSINE, "<=>");
        values.put(DistanceMetric.INNER_PRODUCT, "<#>");
        values.put(DistanceMetric.L2, "<->");
        return Map.copyOf(values);
    }

    public enum DistanceMetric { COSINE, INNER_PRODUCT, L2 }

    public record SearchRequest(
            UUID collectionId,
            UUID modelRevisionId,
            int dimension,
            DistanceMetric metric,
            float[] queryVector,
            int topK,
            Map<String, String> metadataFilters,
            UUID knowledgeRevisionId,
            Set<String> aclPrincipals) {
        public SearchRequest {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
            queryVector = queryVector.clone();
            metadataFilters = Map.copyOf(new LinkedHashMap<>(metadataFilters));
            aclPrincipals = Set.copyOf(aclPrincipals);
        }

        public SearchRequest(
                UUID collectionId, UUID modelRevisionId, int dimension, DistanceMetric metric,
                float[] queryVector, int topK, Map<String, String> metadataFilters) {
            this(collectionId, modelRevisionId, dimension, metric, queryVector, topK,
                    metadataFilters, null, Set.of());
        }

        @Override
        public float[] queryVector() {
            return queryVector.clone();
        }
    }

    public record SearchHit(
            UUID chunkId, UUID contentArtifactId, double distance,
            UUID documentId, UUID documentVersionId, String contentSha256, String sourceLocation) { }

    public static final class VectorContractException extends RuntimeException {
        public VectorContractException(String code) { super(code); }
        public VectorContractException(String code, Throwable cause) { super(code, cause); }
    }
}
