package io.github.opspilot.core.application.knowledge;

import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.ActivationReceipt;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedChunk;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.PreparedRevision;
import io.github.opspilot.core.port.knowledge.KnowledgeRevisionControlPort.RevisionStatus;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Application-level Knowledge revision lifecycle shared by operators and controlled test adapters. */
public final class KnowledgeRevisionControlService {
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{7,255}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> DISTANCE_METRICS = Set.of("COSINE", "INNER_PRODUCT", "L2");

    private final KnowledgeRevisionControlPort revisions;
    private final EmbeddingPort embeddings;
    private final Clock clock;
    private final Policy policy;

    public KnowledgeRevisionControlService(
            KnowledgeRevisionControlPort revisions,
            EmbeddingPort embeddings,
            Clock clock,
            Policy policy) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public RevisionStatus prepare(PrepareRevision command) {
        validatePrepare(command);
        var existing = revisions.findPrepared(
                command.collectionId(), command.revisionId(), command.manifestSha256());
        if (existing.isPresent()) {
            RevisionStatus status = existing.get();
            if (!status.revisionKey().equals(command.revisionKey())
                    || !status.modelRevisionId().equals(command.modelRevisionId())
                    || !status.modelRevisionKey().equals(command.embeddingIdentity().revision())) {
                throw new ControlException("KNOWLEDGE_CONTROL_REVISION_IMMUTABLE");
            }
            if (status.expiresAt() != null && !command.expiresAt().isAfter(status.expiresAt())) {
                return status;
            }
            return revisions.prepare(new PreparedRevision(
                    command.operationId(), command.principalId(), command.collectionId(),
                    command.revisionId(), command.revisionKey(), command.manifestSha256(),
                    command.modelRevisionId(), command.embeddingIdentity().revision(),
                    command.embeddingDimension(), command.distanceMetric(), command.expiresAt(), List.of()));
        }

        List<float[]> vectors = List.of();
        if (!command.chunks().isEmpty()) {
            var result = embeddings.embed(new EmbeddingRequest(
                    command.embeddingIdentity(), command.expiresAt(),
                    command.chunks().stream().map(Chunk::text).toList()));
            if (result.failure() != null) {
                throw new ControlException(result.failure().errorCode());
            }
            vectors = result.value();
            if (vectors.size() != command.chunks().size()) {
                throw new ControlException("KNOWLEDGE_CONTROL_EMBEDDING_COUNT_INVALID");
            }
        }

        List<PreparedChunk> prepared = new ArrayList<>(command.chunks().size());
        for (int index = 0; index < command.chunks().size(); index++) {
            Chunk chunk = command.chunks().get(index);
            float[] vector = vectors.get(index);
            if (vector.length != command.embeddingDimension()) {
                throw new ControlException("KNOWLEDGE_CONTROL_EMBEDDING_DIMENSION_INVALID");
            }
            prepared.add(new PreparedChunk(
                    chunk.chunkId(), chunk.externalKey(), chunk.text(), sha256(chunk.text()),
                    chunk.metadata(), chunk.aclPrincipals().stream().sorted().toList(), vector));
        }
        return revisions.prepare(new PreparedRevision(
                command.operationId(), command.principalId(), command.collectionId(),
                command.revisionId(), command.revisionKey(), command.manifestSha256(),
                command.modelRevisionId(), command.embeddingIdentity().revision(),
                command.embeddingDimension(), command.distanceMetric(),
                command.expiresAt(), prepared));
    }

    public ActivationReceipt activate(ActivateRevision command) {
        validateOperation(command.operationId(), command.principalId());
        authorize(command.collectionId());
        validateExpiry(command.receiptExpiresAt());
        return revisions.activate(
                command.operationId(), command.principalId(), command.collectionId(),
                command.targetRevisionId(), command.expectedActiveRevisionId(),
                command.receiptExpiresAt(), clock.instant());
    }

    public ActivationReceipt restore(RestoreRevision command) {
        validateOperation(command.operationId(), command.principalId());
        Objects.requireNonNull(command.receiptId(), "receiptId");
        ActivationReceipt receipt = revisions.findReceipt(command.receiptId())
                .orElseThrow(() -> new ControlException("KNOWLEDGE_CONTROL_RECEIPT_NOT_FOUND"));
        authorize(receipt.collectionId());
        Instant now = clock.instant();
        if (receipt.expiresAt().isBefore(now)) {
            throw new ControlException("KNOWLEDGE_CONTROL_RECEIPT_EXPIRED");
        }
        return revisions.restore(command.operationId(), command.principalId(), receipt, now);
    }

    private void validatePrepare(PrepareRevision command) {
        Objects.requireNonNull(command, "command");
        validateOperation(command.operationId(), command.principalId());
        authorize(command.collectionId());
        Objects.requireNonNull(command.revisionId(), "revisionId");
        Objects.requireNonNull(command.modelRevisionId(), "modelRevisionId");
        Objects.requireNonNull(command.embeddingIdentity(), "embeddingIdentity");
        if (!SAFE_ID.matcher(command.revisionKey()).matches()) {
            throw new ControlException("KNOWLEDGE_CONTROL_REVISION_KEY_INVALID");
        }
        if (!DIGEST.matcher(command.manifestSha256()).matches()) {
            throw new ControlException("KNOWLEDGE_CONTROL_MANIFEST_DIGEST_INVALID");
        }
        if (command.embeddingDimension() < 1 || !DISTANCE_METRICS.contains(command.distanceMetric())) {
            throw new ControlException("KNOWLEDGE_CONTROL_MODEL_IDENTITY_INVALID");
        }
        validateExpiry(command.expiresAt());
        Set<UUID> chunkIds = new java.util.HashSet<>();
        Set<String> externalKeys = new java.util.HashSet<>();
        for (Chunk chunk : command.chunks()) {
            if (!chunkIds.add(chunk.chunkId()) || !externalKeys.add(chunk.externalKey())
                    || chunk.externalKey().isBlank() || chunk.text().isBlank()
                    || chunk.aclPrincipals().isEmpty()) {
                throw new ControlException("KNOWLEDGE_CONTROL_CHUNK_INVALID");
            }
        }
    }

    private void validateExpiry(Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(policy.maxTtlSeconds()))) {
            throw new ControlException("KNOWLEDGE_CONTROL_TTL_INVALID");
        }
    }

    private static void validateOperation(String operationId, String principalId) {
        if (operationId == null || !SAFE_ID.matcher(operationId).matches()
                || principalId == null || !SAFE_ID.matcher(principalId).matches()) {
            throw new ControlException("KNOWLEDGE_CONTROL_IDENTITY_INVALID");
        }
    }

    private void authorize(UUID collectionId) {
        if (collectionId == null || !policy.allowedCollections().contains(collectionId)) {
            throw new ControlException("KNOWLEDGE_CONTROL_COLLECTION_FORBIDDEN");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record PrepareRevision(
            String operationId,
            String principalId,
            UUID collectionId,
            UUID revisionId,
            String revisionKey,
            String manifestSha256,
            UUID modelRevisionId,
            ProviderIdentity embeddingIdentity,
            int embeddingDimension,
            String distanceMetric,
            Instant expiresAt,
            List<Chunk> chunks) {
        public PrepareRevision {
            chunks = List.copyOf(chunks);
        }
    }

    public record Chunk(
            UUID chunkId,
            String externalKey,
            String text,
            Map<String, String> metadata,
            Set<String> aclPrincipals) {
        public Chunk {
            Objects.requireNonNull(chunkId, "chunkId");
            Objects.requireNonNull(externalKey, "externalKey");
            Objects.requireNonNull(text, "text");
            metadata = Map.copyOf(new LinkedHashMap<>(metadata));
            aclPrincipals = Set.copyOf(aclPrincipals);
        }
    }

    public record ActivateRevision(
            String operationId,
            String principalId,
            UUID collectionId,
            UUID targetRevisionId,
            UUID expectedActiveRevisionId,
            Instant receiptExpiresAt) {
    }

    public record RestoreRevision(
            String operationId,
            String principalId,
            UUID receiptId) {
    }

    public record Policy(Set<UUID> allowedCollections, long maxTtlSeconds) {
        public Policy {
            allowedCollections = Set.copyOf(allowedCollections);
            if (allowedCollections.isEmpty() || maxTtlSeconds < 1) {
                throw new IllegalArgumentException("Knowledge control policy must be bounded");
            }
        }
    }

    public static final class ControlException extends RuntimeException {
        public ControlException(String code) {
            super(code);
        }
    }
}
