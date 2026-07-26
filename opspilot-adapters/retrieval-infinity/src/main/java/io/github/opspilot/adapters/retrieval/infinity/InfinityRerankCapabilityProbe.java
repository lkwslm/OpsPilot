package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.RerankPort;
import io.github.opspilot.core.port.provider.RerankPort.DocumentCandidate;
import io.github.opspilot.core.port.provider.RerankPort.RerankRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Startup probe binding live rerank behavior to immutable deployment evidence. */
public final class InfinityRerankCapabilityProbe {
    public record LockedEvidence(
            String imageDigest,
            String modelId,
            String revision,
            String servedName,
            String license,
            String resourceEvidenceRef) {
        public LockedEvidence {
            requireText(imageDigest, "imageDigest");
            requireText(modelId, "modelId");
            requireText(revision, "revision");
            requireText(servedName, "servedName");
            requireText(license, "license");
            requireText(resourceEvidenceRef, "resourceEvidenceRef");
        }
    }

    public record CapabilityReport(
            String stableProviderId,
            ProviderIdentity identity,
            String imageDigest,
            String license,
            String resourceEvidenceRef,
            int candidateCount,
            boolean livenessUp,
            boolean readinessUp,
            List<String> failures) {
        public CapabilityReport {
            failures = List.copyOf(failures);
        }
    }

    private final InfinityRerankConfiguration configuration;
    private final RerankPort provider;
    private final Clock clock;

    public InfinityRerankCapabilityProbe(
            InfinityRerankConfiguration configuration, RerankPort provider, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CapabilityReport probe(
            LockedEvidence evidence,
            String fixedQuery,
            List<DocumentCandidate> fixedCandidates,
            Duration timeout) {
        Objects.requireNonNull(evidence, "evidence");
        List<String> failures = validateEvidence(evidence);
        ProviderIdentity identity = new ProviderIdentity(configuration.providerId(),
                configuration.servedModel(), configuration.revision());
        if (failures.isEmpty()) {
            var result = provider.rerank(new RerankRequest(identity,
                    clock.instant().plus(timeout), fixedQuery, fixedCandidates));
            if (result.failure() != null) {
                failures.add(result.failure().errorCode());
            } else if (result.value().size() != fixedCandidates.size()) {
                failures.add("RERANK_RESULT_COUNT_MISMATCH");
            }
        }
        return new CapabilityReport(configuration.providerId(), identity,
                evidence.imageDigest(), evidence.license(), evidence.resourceEvidenceRef(),
                fixedCandidates.size(), true, failures.isEmpty(), failures);
    }

    private List<String> validateEvidence(LockedEvidence evidence) {
        List<String> failures = new ArrayList<>();
        if (!configuration.servedModel().equals(evidence.servedName())) {
            failures.add("RERANK_SERVED_MODEL_MISMATCH");
        }
        if (!configuration.revision().equals(evidence.revision())) {
            failures.add("RERANK_REVISION_MISMATCH");
        }
        return failures;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
