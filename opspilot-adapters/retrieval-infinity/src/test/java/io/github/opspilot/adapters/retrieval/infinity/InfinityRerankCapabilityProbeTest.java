package io.github.opspilot.adapters.retrieval.infinity;

import io.github.opspilot.adapters.retrieval.infinity.InfinityRerankCapabilityProbe.LockedEvidence;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.provider.RerankPort;
import io.github.opspilot.core.port.provider.RerankPort.DocumentCandidate;
import io.github.opspilot.core.port.provider.RerankPort.RankedDocument;
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

class InfinityRerankCapabilityProbeTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private final InfinityRerankConfiguration configuration = new InfinityRerankConfiguration(
            URI.create("http://127.0.0.1:7997/rerank"), "infinity", "reranker", "revision");

    @Test
    void bindsSuccessfulLiveContractToLockedEvidence() {
        AtomicInteger calls = new AtomicInteger();
        RerankPort provider = request -> {
            calls.incrementAndGet();
            return new ProviderResult<>(List.of(
                    new RankedDocument("a", 0, 3.5, 1, request.identity()),
                    new RankedDocument("b", 1, -4.0, 2, request.identity())),
                    new ProviderUsage(8, 0, null), null);
        };

        var report = probe(provider).probe(evidence("revision"), "查询", candidates(),
                Duration.ofSeconds(2));

        assertTrue(report.readinessUp());
        assertEquals(1, calls.get());
        assertEquals("sha256:locked", report.imageDigest());
        assertEquals("MIT", report.license());
        assertEquals(2, report.candidateCount());
    }

    @Test
    void revisionMismatchKeepsReadinessDownWithoutProviderCall() {
        AtomicInteger calls = new AtomicInteger();
        RerankPort provider = request -> {
            calls.incrementAndGet();
            throw new AssertionError("must not call provider");
        };

        var report = probe(provider).probe(evidence("other"), "查询", candidates(),
                Duration.ofSeconds(2));

        assertFalse(report.readinessUp());
        assertEquals(List.of("RERANK_REVISION_MISMATCH"), report.failures());
        assertEquals(0, calls.get());
    }

    private InfinityRerankCapabilityProbe probe(RerankPort provider) {
        return new InfinityRerankCapabilityProbe(configuration, provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LockedEvidence evidence(String revision) {
        return new LockedEvidence("sha256:locked", "BAAI/reranker", revision,
                "reranker", "MIT", "outputs/resources.json");
    }

    private static List<DocumentCandidate> candidates() {
        return List.of(new DocumentCandidate("a", "甲"), new DocumentCandidate("b", "乙"));
    }
}
