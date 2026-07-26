package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Three-run startup probe that links live vector behavior to immutable deployment evidence. */
public final class InfinityEmbeddingCapabilityProbe {
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

    public record ProbeRun(int run, int count, int dimension, String vectorSummarySha256) { }

    public record CapabilityReport(
            String stableProviderId,
            ProviderIdentity identity,
            int dimension,
            String normalization,
            String distanceMetric,
            String imageDigest,
            String license,
            String resourceEvidenceRef,
            List<ProbeRun> runs,
            boolean livenessUp,
            boolean readinessUp,
            List<String> failures) {
        public CapabilityReport {
            runs = List.copyOf(runs);
            failures = List.copyOf(failures);
        }
    }

    private final InfinityEmbeddingConfiguration configuration;
    private final EmbeddingPort provider;
    private final Clock clock;

    public InfinityEmbeddingCapabilityProbe(
            InfinityEmbeddingConfiguration configuration, EmbeddingPort provider, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CapabilityReport probe(LockedEvidence evidence, List<String> fixedTexts, Duration timeout) {
        Objects.requireNonNull(evidence, "evidence");
        List<String> failures = validateEvidence(evidence);
        List<ProbeRun> runs = new ArrayList<>();
        ProviderIdentity identity = new ProviderIdentity(configuration.providerId(),
                configuration.servedModel(), configuration.revision());
        for (int run = 1; run <= 3 && failures.isEmpty(); run++) {
            var result = provider.embed(new EmbeddingRequest(identity, clock.instant().plus(timeout), fixedTexts));
            if (result.failure() != null) {
                failures.add(result.failure().errorCode());
                break;
            }
            if (result.value().size() != fixedTexts.size()) {
                failures.add("EMBEDDING_COUNT_MISMATCH");
                break;
            }
            runs.add(new ProbeRun(run, result.value().size(), result.value().getFirst().length,
                    sha256(result.value())));
        }
        boolean ready = failures.isEmpty() && runs.size() == 3
                && runs.stream().allMatch(run -> run.dimension() == configuration.dimension());
        if (!ready && failures.isEmpty()) failures.add("EMBEDDING_DIMENSION_MISMATCH");
        return new CapabilityReport(configuration.providerId(), identity, configuration.dimension(),
                configuration.normalization().name(), configuration.distanceMetric().name(),
                evidence.imageDigest(), evidence.license(), evidence.resourceEvidenceRef(), runs,
                true, ready, failures);
    }

    private List<String> validateEvidence(LockedEvidence evidence) {
        List<String> failures = new ArrayList<>();
        if (!configuration.servedModel().equals(evidence.servedName())) failures.add("SERVED_MODEL_MISMATCH");
        if (!configuration.revision().equals(evidence.revision())) failures.add("MODEL_REVISION_MISMATCH");
        return failures;
    }

    private static String sha256(List<float[]> vectors) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (float[] vector : vectors) {
                for (float value : vector) digest.update(ByteBuffer.allocate(4).putFloat(value).array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
