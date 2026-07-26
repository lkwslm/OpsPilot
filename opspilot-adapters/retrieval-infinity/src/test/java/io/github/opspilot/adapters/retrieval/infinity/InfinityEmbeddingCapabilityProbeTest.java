package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingCapabilityProbe.LockedEvidence;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfinityEmbeddingCapabilityProbeTest {
    private final InfinityEmbeddingConfiguration configuration = new InfinityEmbeddingConfiguration(
            "infinity", URI.create("http://127.0.0.1:7997"), "embedding", "revision", 2,
            Normalization.L2_UNIT, DistanceMetric.COSINE, 4, 4, 512);

    @Test
    void linksThreeSuccessfulRunsToLockedEvidence() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingPort provider = request -> {
            calls.incrementAndGet();
            return new ProviderResult<>(List.of(new float[]{1, 0}, new float[]{0, 1}),
                    new ProviderUsage(2, 0, null), null);
        };

        var report = probe(provider).probe(evidence("revision"), List.of("固定文本一", "fixed text two"),
                Duration.ofSeconds(2));

        assertTrue(report.readinessUp());
        assertEquals(3, calls.get());
        assertEquals(3, report.runs().size());
        assertEquals("sha256:locked", report.imageDigest());
        assertEquals("MIT", report.license());
    }

    @Test
    void lockedRevisionMismatchKeepsReadinessDownWithoutCallingProvider() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingPort provider = request -> {
            calls.incrementAndGet();
            throw new AssertionError();
        };

        var report = probe(provider).probe(evidence("other"), List.of("fixed"), Duration.ofSeconds(2));

        assertTrue(report.livenessUp());
        assertFalse(report.readinessUp());
        assertEquals(List.of("MODEL_REVISION_MISMATCH"), report.failures());
        assertEquals(0, calls.get());
    }

    private InfinityEmbeddingCapabilityProbe probe(EmbeddingPort provider) {
        return new InfinityEmbeddingCapabilityProbe(configuration, provider,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));
    }

    private static LockedEvidence evidence(String revision) {
        return new LockedEvidence("sha256:locked", "BAAI/bge-small-zh-v1.5", revision,
                "embedding", "MIT", "outputs/phase0/01-WP06/01-WP06.T07-retrieval-gate-report.json");
    }
}
