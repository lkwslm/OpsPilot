package io.github.opspilot.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7AcceptanceServiceTest {

    @Test
    void acceptsCanonicalRawArtifactDigestAndRejectsBareOrMalformedValues() {
        String digest = "a".repeat(64);

        assertTrue(Phase7AcceptanceService.validRawArtifactSha256("sha256:" + digest));
        assertFalse(Phase7AcceptanceService.validRawArtifactSha256(digest));
        assertFalse(Phase7AcceptanceService.validRawArtifactSha256("sha256:" + "g".repeat(64)));
    }
}
