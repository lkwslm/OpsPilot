package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import io.github.opspilot.core.port.provider.SecretResolver.ResolvedSecret;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleFoundationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void defaultsOnlyTheDeepSeekBaseUrl() {
        OpenAiCompatibleClientConfiguration configuration = OpenAiCompatibleClientConfiguration.defaults();

        assertEquals(URI.create("https://api.deepseek.com"), configuration.baseUrl());
        assertNull(configuration.model());
        assertNull(configuration.secretRef());
    }

    @Test
    void composesVersionPathExactlyOnce() {
        assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"),
                OpenAiCompatibleUris.resource(URI.create("https://api.deepseek.com"), "chat/completions"));
        assertEquals(URI.create("https://compatible.example/v1/chat/completions"),
                OpenAiCompatibleUris.resource(URI.create("https://compatible.example/v1/"),
                        "/v1/chat/completions"));
        assertEquals(URI.create("https://compatible.example/openai/v1/chat/completions"),
                OpenAiCompatibleUris.resource(URI.create("https://compatible.example/openai/v1"),
                        "chat/completions"));
    }

    @Test
    void rejectsUnsafeBaseAndResourceUris() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiCompatibleUris.resource(URI.create("https://user@example.test"), "chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiCompatibleUris.resource(URI.create("https://example.test"), "../admin"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiCompatibleUris.resource(URI.create("https://example.test?debug=true"),
                        "chat/completions"));
    }

    @Test
    void injectsAuthorizationHeaderWithoutSerializingTheSecret() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("x-request-id", "request-123");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions");
            OpenAiChatDtos.CompletionRequest payload = new OpenAiChatDtos.CompletionRequest(
                    "explicit-model",
                    List.of(new OpenAiChatDtos.Message("user", "ping", null)),
                    List.of(), false, null);
            OpenAiCompatibleHttpClient client = new OpenAiCompatibleHttpClient(
                    HttpClient.newHttpClient(), JSON);
            String secretValue = "header-only-value";
            OpenAiCompatibleHttpClient.HttpResult result;
            try (ResolvedSecret secret = new ResolvedSecret(secretValue.toCharArray())) {
                result = client.postJson(endpoint, payload, secret, Duration.ofSeconds(2));
            }

            assertEquals(200, result.statusCode());
            assertEquals("request-123", result.requestId());
            assertEquals("Bearer " + secretValue, authorization.get());
            assertFalse(requestBody.get().contains(secretValue));
            assertEquals("explicit-model", JSON.readTree(requestBody.get()).path("model").textValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptingTheCallerCancelsTheUnderlyingHttpRequest() throws Exception {
        PendingHttpClient pendingHttp = new PendingHttpClient();
        OpenAiCompatibleHttpClient client = new OpenAiCompatibleHttpClient(pendingHttp, JSON);
        try (var caller = Executors.newSingleThreadExecutor();
             ResolvedSecret secret = new ResolvedSecret("cancel-me".toCharArray())) {
            var call = caller.submit(() -> assertThrows(InterruptedException.class,
                    () -> client.postJson(URI.create("https://example.test/v1/chat/completions"),
                            Map.of("model", "test"), secret, Duration.ofSeconds(2))));
            assertTrue(pendingHttp.started.await(1, TimeUnit.SECONDS));

            call.cancel(true);

            assertTrue(pendingHttp.cancelled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void mapsDomainMessagesAndToolsToWireDtos() {
        ChatRequest request = new ChatRequest(
                "explicit-model",
                List.of(new ChatMessage("system", "Be exact"), new ChatMessage("user", "ping")),
                List.of(new ToolDefinition("lookup", "Look up evidence",
                        Map.of("type", "object", "required", List.of("id")))));

        OpenAiChatDtos.CompletionRequest mapped = new OpenAiChatMapper(JSON).toRequest(request, true);

        assertEquals("explicit-model", mapped.model());
        assertEquals(2, mapped.messages().size());
        assertEquals("function", mapped.tools().getFirst().type());
        assertEquals("lookup", mapped.tools().getFirst().function().name());
        assertTrue(mapped.stream());
        assertFalse(JSON.valueToTree(mapped).has("tool_choice"));
    }

    @Test
    void mapsWireResponseUsageToolCallsAndActualIdentity() {
        String body = """
                {
                  "id": "response-1",
                  "model": "actual-model-revision",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {"name": "lookup", "arguments": "{\\"id\\":\\"INC-1\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 7,
                    "prompt_cache_hit_tokens": 3
                  }
                }
                """;
        OpenAiChatMapper mapper = new OpenAiChatMapper(JSON);

        OpenAiChatMapper.MappedResponse mapped = mapper.toDomain(
                mapper.readResponse(body), "deepseek", "configured-revision");

        assertEquals("actual-model-revision", mapped.actualIdentity().modelId());
        assertEquals("configured-revision", mapped.actualIdentity().revision());
        assertEquals("tool_calls", mapped.response().finishReason());
        assertEquals(12, mapped.response().usage().inputTokens());
        assertEquals(3, mapped.response().usage().cachedTokens());
        assertEquals("INC-1", mapped.response().toolCalls().getFirst().arguments().get("id"));
    }

    @Test
    void rejectsMalformedToolArgumentsInsteadOfReturningPartialDomainData() {
        OpenAiChatDtos.CompletionResponse response = new OpenAiChatDtos.CompletionResponse(
                "response-1",
                "actual-model",
                List.of(new OpenAiChatDtos.Choice(0,
                        new OpenAiChatDtos.Message("assistant", null,
                                List.of(new OpenAiChatDtos.ToolCall("call-1", "function",
                                        new OpenAiChatDtos.FunctionCall("lookup", "not-json")))),
                        "tool_calls")),
                null);

        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiChatMapper(JSON).toDomain(response, "deepseek", "revision"));
    }

    private static final class PendingHttpClient extends HttpClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new AssertionError("blocking send must not be used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            started.countDown();
            return new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    cancelled.countDown();
                    return super.cancel(mayInterruptIfRunning);
                }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }
}
