package io.github.opspilot.a2a.contract;

import com.google.gson.JsonParser;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Phase0AgentCardsTest {

    @Test
    void sixCardsPassLockedOfficialObjectContract() {
        Map<String, AgentCard> expected = Phase0AgentCards.create();

        assertEquals("1.0", AgentInterface.CURRENT_PROTOCOL_VERSION);
        assertEquals(6, expected.size());
        expected.forEach((id, generated) -> {
            assertFixtureMatches(id, generated);
            assertEquals(1, generated.skills().size());
            assertFalse(generated.skills().getFirst().id().isBlank());
            assertFalse(generated.skills().getFirst().inputModes().isEmpty());
            assertFalse(generated.skills().getFirst().outputModes().isEmpty());
            assertEquals(1, generated.supportedInterfaces().size());
            AgentInterface agentInterface = generated.supportedInterfaces().getFirst();
            assertEquals(Phase0AgentCards.PROTOCOL_VERSION, agentInterface.protocolVersion());
            assertEquals(TransportProtocol.HTTP_JSON.asString(), agentInterface.protocolBinding());
            assertTrue(agentInterface.url().startsWith("http://"));
            assertTrue(generated.capabilities().streaming());
            assertEquals(1, generated.capabilities().extensions().size());
            assertTrue(generated.capabilities().extensions().getFirst().required());
            assertFalse(generated.securitySchemes().isEmpty());
            var descriptor = OfficialA2aMapper.fromOfficialCard(id, generated);
            assertEquals(generated, OfficialA2aMapper.toOfficialCard(descriptor));
        });
    }

    private void assertFixtureMatches(String id, AgentCard generated) {
        var stream = getClass().getResourceAsStream("/agent-cards/" + id + ".json");
        assertNotNull(stream, id);
        var fixture = JsonParser.parseReader(new java.io.InputStreamReader(stream)).getAsJsonObject();
        assertEquals(generated.name(), fixture.get("name").getAsString());
        assertEquals(generated.version(), fixture.get("version").getAsString());
        assertEquals(generated.skills().getFirst().id(),
                fixture.getAsJsonArray("skills").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(generated.supportedInterfaces().getFirst().protocolVersion(),
                fixture.getAsJsonArray("supportedInterfaces").get(0).getAsJsonObject()
                        .get("protocolVersion").getAsString());
        assertTrue(fixture.getAsJsonObject("securitySchemes").has("serviceIdentity"));
        assertTrue(fixture.getAsJsonObject("capabilities").getAsJsonArray("extensions")
                .get(0).getAsJsonObject().get("required").getAsBoolean());
    }
}
