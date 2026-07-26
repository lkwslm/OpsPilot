package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.port.provider.SecretResolver.ResolvedSecret;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** One-attempt JSON transport. Retry and provider selection remain outside this adapter. */
public final class OpenAiCompatibleHttpClient {
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiCompatibleHttpClient() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper());
    }

    public OpenAiCompatibleHttpClient(HttpClient http, ObjectMapper json) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    public HttpResult postJson(URI endpoint, Object payload, ResolvedSecret apiKey, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = buildPostRequest(endpoint, payload, apiKey, timeout, "application/json");
        return send(request);
    }

    public HttpResult postEventStream(URI endpoint, Object payload, ResolvedSecret apiKey, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = buildPostRequest(endpoint, payload, apiKey, timeout, "text/event-stream");
        return send(request);
    }

    private HttpResult send(HttpRequest request) throws IOException, InterruptedException {
        Future<HttpResponse<String>> future = http.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
            throw exception;
        } catch (CancellationException exception) {
            throw new IOException("HTTP request cancelled", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            throw new IOException("HTTP request failed", cause);
        }
        String requestId = response.headers().firstValue("x-request-id")
                .or(() -> response.headers().firstValue("trace-id"))
                .orElse(null);
        return new HttpResult(response.statusCode(), response.body(), requestId);
    }

    HttpRequest buildPostRequest(URI endpoint, Object payload, ResolvedSecret apiKey, Duration timeout)
            throws JsonProcessingException {
        return buildPostRequest(endpoint, payload, apiKey, timeout, "application/json");
    }

    private HttpRequest buildPostRequest(
            URI endpoint, Object payload, ResolvedSecret apiKey, Duration timeout, String accept)
            throws JsonProcessingException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        char[] secret = apiKey.value();
        try {
            if (secret.length == 0) {
                throw new IllegalArgumentException("API key must not be empty");
            }
            return HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", accept)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + new String(secret))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                    .build();
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    public record HttpResult(int statusCode, String body, String requestId) {
    }
}
