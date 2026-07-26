package io.github.opspilot.core.application.knowledge;

import io.github.opspilot.core.application.knowledge.KnowledgeSearchService.KnowledgeReference;
import io.github.opspilot.core.port.knowledge.KnowledgeContracts.KnowledgeResult;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Permission and integrity gate before a knowledge assertion can enter EvidenceNormalizer. */
public final class KnowledgeReferenceEvidenceMapper {
    private final KnowledgeReferenceVerifier verifier;

    public KnowledgeReferenceEvidenceMapper(KnowledgeReferenceVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public KnowledgeResult verifiedResult(
            UUID runId,
            KnowledgeReference reference,
            Set<String> aclPrincipals,
            String boundedAssertion) {
        if (boundedAssertion == null || boundedAssertion.isBlank() || boundedAssertion.length() > 2_000) {
            throw new ReferenceVerificationException("KNOWLEDGE_ASSERTION_INVALID");
        }
        if (!verifier.verify(runId, reference, aclPrincipals)) {
            throw new ReferenceVerificationException("KNOWLEDGE_REFERENCE_VERIFICATION_FAILED");
        }
        return new KnowledgeResult(reference.referenceId().toString(),
                reference.collectionId().toString(), reference.knowledgeRevisionId().toString(),
                boundedAssertion, List.of(reference.artifactId()));
    }

    @FunctionalInterface
    public interface KnowledgeReferenceVerifier {
        boolean verify(UUID runId, KnowledgeReference reference, Set<String> aclPrincipals);
    }

    public static final class ReferenceVerificationException extends RuntimeException {
        public ReferenceVerificationException(String code) { super(code); }
    }
}
