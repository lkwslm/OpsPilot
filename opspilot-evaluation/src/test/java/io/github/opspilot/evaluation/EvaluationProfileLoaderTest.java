package io.github.opspilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvaluationProfileLoaderTest {
    private final EvaluationProfileLoader loader = new EvaluationProfileLoader(
            new ObjectMapper().findAndRegisterModules());
    private final Path profiles = Path.of("../docs/design/contracts/profiles");

    @Test
    void loadsPublishedTokenBudgetFromMvpV2() {
        var profile = loader.load(profiles.resolve("mvp-v2.yaml"));

        assertEquals("mvp-v2", profile.profileId());
        assertEquals(65_536, profile.efficiencyLimits().get("total_tokens"));
    }

    @Test
    void publishingMvpV2DoesNotOverwriteMvpV1() {
        var v1 = loader.load(profiles.resolve("mvp-v1.yaml"));
        var v2 = loader.load(profiles.resolve("mvp-v2.yaml"));

        assertEquals("mvp-v1", v1.profileId());
        assertFalse(v1.efficiencyLimits().containsKey("total_tokens"));
        assertNotEquals(v1.profileId(), v2.profileId());
        assertNotEquals(v1.snapshotSha256(), v2.snapshotSha256());
    }
}
