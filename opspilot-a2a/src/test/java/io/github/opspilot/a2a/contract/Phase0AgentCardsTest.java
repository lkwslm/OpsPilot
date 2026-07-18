package io.github.opspilot.a2a.contract;

import com.google.gson.Gson;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
            AgentCard card = load(id);
            assertEquals(generated.name(), card.name());
            assertEquals(generated.version(), card.version());
            assertEquals(1, card.skills().size());
            assertFalse(card.skills().getFirst().id().isBlank());
            assertFalse(card.skills().getFirst().inputModes().isEmpty());
            assertFalse(card.skills().getFirst().outputModes().isEmpty());
            assertEquals(1, card.supportedInterfaces().size());
            AgentInterface agentInterface = card.supportedInterfaces().getFirst();
            assertEquals(Phase0AgentCards.PROTOCOL_VERSION, agentInterface.protocolVersion());
            assertEquals(TransportProtocol.HTTP_JSON.asString(), agentInterface.protocolBinding());
            assertTrue(agentInterface.url().startsWith("http://"));
            assertTrue(card.capabilities().streaming());
        });
    }

    private AgentCard load(String id) {
        var stream = getClass().getResourceAsStream("/agent-cards/" + id + ".json");
        assertNotNull(stream, id);
        return new Gson().fromJson(
                new InputStreamReader(stream, StandardCharsets.UTF_8), AgentCard.class);
    }
}
