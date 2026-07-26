package io.github.opspilot.adapters.retrieval.infinity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NormalizedTextHasherTest {
    @Test
    void nfkcLineEndingsAndOuterWhitespaceProduceOneVersionedReuseKey() {
        assertEquals(NormalizedTextHasher.sha256("  Ａ\r\n故障  "),
                NormalizedTextHasher.sha256("A\n故障"));
        assertNotEquals(NormalizedTextHasher.sha256("A\n故障"),
                NormalizedTextHasher.sha256("A\n其他故障"));
        assertEquals("embedding-text-nfkc-lf-trim-v1", NormalizedTextHasher.NORMALIZATION_VERSION);
    }
}
