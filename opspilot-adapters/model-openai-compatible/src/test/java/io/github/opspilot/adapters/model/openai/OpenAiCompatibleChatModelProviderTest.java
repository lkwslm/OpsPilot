package io.github.opspilot.adapters.model.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.port.agent.ChatPort.ChatInvocation;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.port.agent.ChatPort.StructuredOutput;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.SecretResolver.ResolvedSecret;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleChatModelProviderTest {

    @Test
    void completesOrdinaryChatWithUsageFinishReasonAndActualIdentity() throws Exception {
        try (TestEndpoint endpoint = TestEndpoint.responding("application/json", """
                {
                  "id":"response-ordinary",
                  "model":"actual-model",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},
                    "finish_reason":"stop"}],
                  "usage":{"prompt_tokens":4,"completion_tokens":2,"prompt_cache_hit_tokens":1}
                }
                """)) {
            ChatResponse response = provider(endpoint).complete(invocation());

            assertEquals("pong", response.text());
            assertEquals("stop", response.finishReason());
            assertEquals(4, response.usage().inputTokens());
            assertEquals("actual-model", response.actualIdentity().modelId());
            assertEquals("deepseek", response.actualIdentity().providerId());
        }
    }

    @Test
    void publishesStreamingResultOnlyAfterFinishReasonAndDoneMarker() throws Exception {
        String stream = """
                data: {"id":"response-stream","model":"actual-model","choices":[{"index":0,"delta":{"role":"assistant","content":"po"},"finish_reason":null}]}

                data: {"id":"response-stream","model":"actual-model","choices":[{"index":0,"delta":{"content":"ng"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}

                data: [DONE]

                """;
        try (TestEndpoint endpoint = TestEndpoint.responding("text/event-stream", stream)) {
            ChatResponse response = provider(endpoint).completeStreaming(invocation());

            assertEquals("pong", response.text());
            assertEquals("stop", response.finishReason());
            assertEquals(5, response.usage().inputTokens());
            assertEquals("actual-model", response.actualIdentity().modelId());
        }
    }

    @Test
    void interruptedStreamNeverProducesAPartialMessage() throws Exception {
        String incompleteStream = """
                data: {"id":"response-stream","model":"actual-model","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}

                """;
        try (TestEndpoint endpoint = TestEndpoint.responding("text/event-stream", incompleteStream)) {
            assertThrows(OpenAiChatStreamAccumulator.IncompleteStreamException.class,
                    () -> provider(endpoint).completeStreaming(invocation()));
        }
    }

    @Test
    void expiredDeadlineFailsBeforeSendingARequest() throws Exception {
        try (TestEndpoint endpoint = TestEndpoint.responding("application/json", "{}")) {
            ChatInvocation expired = new ChatInvocation(request(), Instant.now().minusSeconds(1),
                    new ProviderIdentity("deepseek", "configured-model", "revision-1"));

            assertThrows(OpenAiCompatibleChatModelProvider.DeadlineException.class,
                    () -> provider(endpoint).complete(expired));
            assertEquals(0, endpoint.requestCount);
        }
    }

    @Test
    void rejectsUnverifiedStructuredCapabilityBeforeSending() throws Exception {
        try (TestEndpoint endpoint = TestEndpoint.responding("application/json", "{}")) {
            OpenAiCompatibleChatModelProvider unverified = provider(endpoint, Set.of());
            ChatInvocation invocation = new ChatInvocation(structuredRequest(false), Instant.now().plusSeconds(3),
                    new ProviderIdentity("deepseek", "configured-model", "revision-1"));

            assertThrows(OpenAiCompatibleChatModelProvider.CapabilityException.class,
                    () -> unverified.complete(invocation));
            assertEquals(0, endpoint.requestCount);
        }
    }

    @Test
    void performsAtMostOneAuthorizedStructuredRepair() throws Exception {
        String invalid = completion("not-json");
        String valid = completion("{\"status\":\"ok\"}");
        try (TestEndpoint endpoint = TestEndpoint.respondingSequence("application/json", List.of(invalid, valid))) {
            ChatInvocation invocation = new ChatInvocation(structuredRequest(true), Instant.now().plusSeconds(3),
                    new ProviderIdentity("deepseek", "configured-model", "revision-1"));

            ChatResponse response = provider(endpoint).complete(invocation);

            assertEquals("{\"status\":\"ok\"}", response.text());
            assertEquals(2, endpoint.requestCount);
        }
    }

    @Test
    void failedRepairIsNotRepeated() throws Exception {
        String invalid = completion("not-json");
        try (TestEndpoint endpoint = TestEndpoint.respondingSequence(
                "application/json", List.of(invalid, invalid, completion("{\"status\":\"ok\"}")))) {
            ChatInvocation invocation = new ChatInvocation(structuredRequest(true), Instant.now().plusSeconds(3),
                    new ProviderIdentity("deepseek", "configured-model", "revision-1"));

            assertThrows(StrictJsonSchemaValidator.StructuredOutputException.class,
                    () -> provider(endpoint).complete(invocation));
            assertEquals(2, endpoint.requestCount);
        }
    }

    private static OpenAiCompatibleChatModelProvider provider(TestEndpoint endpoint) {
        return provider(endpoint, Set.of(ModelCapability.TOOL_CALLS,
                ModelCapability.STRUCTURED_OUTPUT, ModelCapability.STREAMING));
    }

    private static OpenAiCompatibleChatModelProvider provider(
            TestEndpoint endpoint, Set<ModelCapability> capabilities) {
        return new OpenAiCompatibleChatModelProvider(
                "deepseek",
                "revision-1",
                new OpenAiCompatibleClientConfiguration(endpoint.baseUrl(), "configured-model", "env:MODEL_KEY"),
                (reference, context) -> new ResolvedSecret("header-only-value".toCharArray()),
                capabilities);
    }

    private static ChatInvocation invocation() {
        return new ChatInvocation(request(), Instant.now().plusSeconds(3),
                new ProviderIdentity("deepseek", "configured-model", "revision-1"));
    }

    private static ChatRequest request() {
        return new ChatRequest("configured-model", List.of(new ChatMessage("user", "ping")), List.of());
    }

    private static ChatRequest structuredRequest(boolean repairAllowed) {
        return new ChatRequest("configured-model", List.of(new ChatMessage("user", "return status")), List.of(),
                new StructuredOutput("status_response", Map.of(
                        "type", "object",
                        "properties", Map.of("status", Map.of("type", "string")),
                        "required", List.of("status"),
                        "additionalProperties", false), true, repairAllowed));
    }

    private static String completion(String content) {
        return "{\"id\":\"response-structured\",\"model\":\"actual-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + quote(content) + "},\"finish_reason\":\"stop\"}]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class TestEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final String contentType;
        private final List<String> bodies;
        private int requestCount;

        private TestEndpoint(HttpServer server, String contentType, List<String> bodies) {
            this.server = server;
            this.contentType = contentType;
            this.bodies = List.copyOf(bodies);
        }

        static TestEndpoint responding(String contentType, String body) throws IOException {
            return respondingSequence(contentType, List.of(body));
        }

        static TestEndpoint respondingSequence(String contentType, List<String> bodies) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestEndpoint endpoint = new TestEndpoint(server, contentType, bodies);
            server.createContext("/v1/chat/completions", endpoint::respond);
            server.start();
            return endpoint;
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void respond(HttpExchange exchange) throws IOException {
            String body = bodies.get(Math.min(requestCount, bodies.size() - 1));
            requestCount++;
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
