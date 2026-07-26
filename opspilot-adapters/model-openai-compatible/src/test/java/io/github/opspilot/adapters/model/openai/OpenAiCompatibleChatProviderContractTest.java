package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.model.openai.OpenAiProviderFailureMapper.FailureContext;
import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.core.domain.failure.ChainFailure.CheckpointRef;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.StructuredOutput;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared wire/domain/failure contract that compatible Chat implementations must preserve. */
class OpenAiCompatibleChatProviderContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toolAndStrictStructuredRequestUseProtocolContracts() throws Exception {
        ChatRequest request = new ChatRequest("model-1",
                List.of(new ChatMessage("user", "inspect")),
                List.of(new ToolDefinition("lookup", "lookup evidence", Map.of("type", "object"))),
                new StructuredOutput("result", Map.of(
                        "type", "object",
                        "properties", Map.of("status", Map.of("type", "string")),
                        "required", List.of("status"),
                        "additionalProperties", false), true, false));

        String wire = JSON.writeValueAsString(new OpenAiChatMapper(JSON).toRequest(request, false));

        assertTrue(wire.contains("\"type\":\"function\""));
        assertTrue(wire.contains("\"type\":\"json_schema\""));
        assertTrue(wire.contains("\"strict\":true"));
        assertFalse(wire.toLowerCase().contains("authorization"));
    }

    @Test
    void missingUsageIsExplicitlyRepresentedAsUnknownZeroAtAdapterBoundary() {
        String body = """
                {"id":"response-1","model":"actual-model","choices":[
                  {"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}
                ]}
                """;
        OpenAiChatMapper mapper = new OpenAiChatMapper(JSON);

        OpenAiChatMapper.MappedResponse mapped = mapper.toDomain(
                mapper.readResponse(body), "provider-1", "revision-1");

        assertEquals(0, mapped.response().usage().inputTokens());
        assertEquals(0, mapped.response().usage().outputTokens());
        assertEquals("actual-model", mapped.response().actualIdentity().modelId());
    }

    @Test
    void cancellationProducesNoResponseAndKeepsCorrelation() {
        UUID correlationId = UUID.randomUUID();
        FailureContext context = new FailureContext("provider-1", 1, correlationId,
                new CheckpointRef(UUID.randomUUID(), 3));

        ChainFailure failure = new OpenAiProviderFailureMapper().map(
                new CancellationException("private cancellation detail"), context);

        assertEquals("PROVIDER_CALL_CANCELLED", failure.errorCode());
        assertEquals(correlationId, failure.correlationId());
        assertFalse(failure.retryable());
        assertFalse(failure.redactedSummary().contains("private cancellation detail"));
    }

    @Test
    void authenticationFailureRetainsSafeRequestCorrelationOnly() {
        FailureContext context = new FailureContext("provider-1", 2, UUID.randomUUID(),
                new CheckpointRef(UUID.randomUUID(), 4));

        ChainFailure failure = new OpenAiProviderFailureMapper().map(
                new OpenAiCompatibleChatModelProvider.HttpStatusException(401, "trace-safe-123"), context);

        assertEquals("PROVIDER_AUTHENTICATION_FAILED", failure.errorCode());
        assertTrue(failure.redactedSummary().contains("requestId=trace-safe-123"));
        assertFalse(failure.redactedSummary().contains("private"));
        assertNull(failure.logRefs().isEmpty() ? null : failure.logRefs().getFirst());
    }
}
