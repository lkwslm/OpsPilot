package io.github.opspilot.core.application.knowledge;

import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.artifact.ArtifactPort;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort.AcceptedIngestion;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort.ChunkWrite;
import io.github.opspilot.core.port.knowledge.KnowledgeIngestionPort.IngestionRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates, stores and versions knowledge input before it becomes eligible for embedding. */
public final class KnowledgeIngestionService {
    public static final String NORMALIZATION_VERSION = "knowledge-text-nfkc-lf-trim-v1";
    public static final String CHUNK_STRATEGY_VERSION = "fixed-char-1000-v1";
    private static final int CHUNK_CHARACTERS = 1_000;
    private static final Set<String> MEDIA_TYPES = Set.of(
            "text/plain", "text/markdown", "application/json");
    private static final Set<String> METADATA_KEYS = Set.of(
            "schemaVersion", "language", "service", "documentType", "tags", "relations");
    private static final Pattern PRINCIPAL = Pattern.compile("^[A-Za-z0-9:_-]{1,128}$");

    private final ArtifactPort artifacts;
    private final KnowledgeIngestionPort repository;
    private final KnowledgeWriteAuthorizer authorizer;

    public KnowledgeIngestionService(
            ArtifactPort artifacts,
            KnowledgeIngestionPort repository,
            KnowledgeWriteAuthorizer authorizer) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    public AcceptedIngestion ingest(IngestionRequest request) {
        validate(request);
        String requestHash = requestHash(request);
        var existing = repository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestSha256().equals(requestHash)) {
                throw new KnowledgeIngestionException("KNOWLEDGE_IDEMPOTENCY_CONFLICT");
            }
            return existing.get();
        }
        if (!authorizer.canWrite(request.runId(), request.collectionId(), request.aclPrincipals())) {
            throw new KnowledgeIngestionException("KNOWLEDGE_WRITE_FORBIDDEN");
        }

        byte[] raw = request.content().clone();
        var sourceArtifact = artifacts.store(request.runId(), request.mediaType(), raw);
        String normalized = normalize(new String(raw, StandardCharsets.UTF_8));
        List<TextSlice> slices = slice(normalized);
        List<ChunkWrite> chunks = new ArrayList<>(slices.size());
        for (int ordinal = 0; ordinal < slices.size(); ordinal++) {
            TextSlice slice = slices.get(ordinal);
            byte[] bytes = slice.text().getBytes(StandardCharsets.UTF_8);
            var artifact = artifacts.store(request.runId(), "text/plain", bytes);
            chunks.add(new ChunkWrite(UUID.randomUUID(), ordinal, artifact,
                    "char:" + slice.start() + '-' + slice.end(), sha256(bytes), slice.text()));
        }
        AcceptedIngestion identity = new AcceptedIngestion(request.idempotencyKey(), requestHash,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return repository.accept(new IngestionRevision(identity, request.collectionId(),
                request.externalKey(), request.mediaType(), sourceArtifact, sha256(raw),
                NORMALIZATION_VERSION, CHUNK_STRATEGY_VERSION, request.modelRevisionId(),
                request.embeddingDimension(), request.aclPrincipals().stream().sorted().toList(), chunks));
    }

    private static void validate(IngestionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!MEDIA_TYPES.contains(request.mediaType())) {
            throw new KnowledgeIngestionException("KNOWLEDGE_FORMAT_NOT_ALLOWED");
        }
        if (request.content().length == 0) {
            throw new KnowledgeIngestionException("KNOWLEDGE_CONTENT_EMPTY");
        }
        if (request.idempotencyKey().isBlank() || request.externalKey().isBlank()) {
            throw new KnowledgeIngestionException("KNOWLEDGE_IDENTITY_INVALID");
        }
        if (!METADATA_KEYS.containsAll(request.metadata().keySet())) {
            throw new KnowledgeIngestionException("KNOWLEDGE_METADATA_INVALID");
        }
        if (request.aclPrincipals().isEmpty()
                || request.aclPrincipals().stream().anyMatch(
                principal -> principal == null || !PRINCIPAL.matcher(principal).matches())) {
            throw new KnowledgeIngestionException("KNOWLEDGE_ACL_INVALID");
        }
        if (request.embeddingDimension() < 1) {
            throw new KnowledgeIngestionException("KNOWLEDGE_DIMENSION_INVALID");
        }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFKC).strip();
    }

    private static List<TextSlice> slice(String text) {
        if (text.isEmpty()) {
            throw new KnowledgeIngestionException("KNOWLEDGE_CONTENT_EMPTY");
        }
        List<TextSlice> slices = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_CHARACTERS) {
            int end = Math.min(text.length(), start + CHUNK_CHARACTERS);
            slices.add(new TextSlice(start, end, text.substring(start, end)));
        }
        return slices;
    }

    private static String requestHash(IngestionRequest request) {
        String stableMetadata = new TreeMap<>(request.metadata()).toString();
        String stableAcl = request.aclPrincipals().stream().sorted().toList().toString();
        byte[] header = (request.collectionId() + "\n" + request.externalKey() + "\n"
                + request.mediaType() + "\n" + stableMetadata + "\n" + stableAcl + "\n"
                + request.modelRevisionId() + "\n" + request.embeddingDimension() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[header.length + request.content().length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(request.content(), 0, combined, header.length, request.content().length);
        return sha256(combined);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record IngestionRequest(
            RunId runId,
            UUID collectionId,
            String externalKey,
            String idempotencyKey,
            String mediaType,
            byte[] content,
            Map<String, String> metadata,
            Set<String> aclPrincipals,
            UUID modelRevisionId,
            int embeddingDimension) {
        public IngestionRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(externalKey, "externalKey");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(mediaType, "mediaType");
            content = content == null ? new byte[0] : content.clone();
            metadata = Map.copyOf(metadata);
            aclPrincipals = Set.copyOf(aclPrincipals);
            Objects.requireNonNull(modelRevisionId, "modelRevisionId");
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    @FunctionalInterface
    public interface KnowledgeWriteAuthorizer {
        boolean canWrite(RunId runId, UUID collectionId, Set<String> aclPrincipals);
    }

    private record TextSlice(int start, int end, String text) {
    }

    public static final class KnowledgeIngestionException extends RuntimeException {
        public KnowledgeIngestionException(String code) {
            super(code);
        }
    }
}
