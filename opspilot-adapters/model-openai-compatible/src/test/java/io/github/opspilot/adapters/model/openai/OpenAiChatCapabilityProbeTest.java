package io.github.opspilot.adapters.model.openai;

import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.port.agent.ChatPort.TokenUsage;
import io.github.opspilot.core.port.agent.ChatPort.ToolCall;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatCapabilityProbeTest {
    private static final ProviderIdentity IDENTITY = new ProviderIdentity(
            "deepseek", "actual-model", "revision-1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-26T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void executesEachRequiredCapabilityAsASeparateProbe() {
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> streamingCalls = new ArrayList<>();
        OpenAiChatCapabilityProbe probe = new OpenAiChatCapabilityProbe((invocation, streaming) -> {
            int call = calls.getAndIncrement();
            streamingCalls.add(streaming);
            List<ToolCall> tools = call == 2
                    ? List.of(new ToolCall("call-1", "capability_probe", Map.of("value", "OK")))
                    : List.of();
            String text = call == 3 ? "{\"status\":\"OK\"}" : "OK";
            return new ChatResponse("response-" + call, text, tools,
                    new TokenUsage(2, 1, 0), "stop", IDENTITY);
        }, IDENTITY, "config-v1", true, Duration.ofSeconds(2), CLOCK);

        OpenAiChatCapabilityProbe.CapabilitySnapshot snapshot = probe.probe();

        assertEquals(5, calls.get());
        assertEquals(List.of(false, false, false, false, true), streamingCalls);
        assertTrue(snapshot.allRequiredCapabilitiesValidated());
        assertEquals(5, snapshot.results().size());
        assertTrue(snapshot.results().stream().allMatch(result -> result.usage().inputTokens() == 2));
        assertEquals("PASS_NO_SECRET_OR_BODY_RECORDED", snapshot.redactionConclusion());
    }

    @Test
    void aMissingRequiredCapabilityKeepsTheSnapshotUnavailable() {
        OpenAiChatCapabilityProbe probe = new OpenAiChatCapabilityProbe((invocation, streaming) -> {
            if (!invocation.request().tools().isEmpty()) {
                return new ChatResponse("response-tool", "not a tool call", List.of(),
                        new TokenUsage(2, 1, 0), "stop", IDENTITY);
            }
            return new ChatResponse("response", "{\"status\":\"OK\"}", List.of(),
                    new TokenUsage(2, 1, 0), "stop", IDENTITY);
        }, IDENTITY, "config-v1", false, Duration.ofSeconds(2), CLOCK);

        OpenAiChatCapabilityProbe.CapabilitySnapshot snapshot = probe.probe();

        assertFalse(snapshot.allRequiredCapabilitiesValidated());
        assertEquals(OpenAiChatCapabilityProbe.ProbeStatus.FAILED,
                snapshot.results().stream()
                        .filter(result -> result.capability() == OpenAiChatCapabilityProbe.Capability.TOOL_CALLS)
                        .findFirst().orElseThrow().status());
        assertEquals(OpenAiChatCapabilityProbe.ProbeStatus.NOT_REQUIRED,
                snapshot.results().getLast().status());
    }
}
