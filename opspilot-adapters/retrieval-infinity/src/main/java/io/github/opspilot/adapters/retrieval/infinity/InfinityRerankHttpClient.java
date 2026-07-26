package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/** One-attempt cancellable transport dedicated to the Infinity rerank endpoint. */
final class InfinityRerankHttpClient {
    private final HttpClient http;
    private final ObjectMapper json;

    InfinityRerankHttpClient(HttpClient http, ObjectMapper json) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    InfinityRerankDtos.Response rerank(
            URI uri, InfinityRerankDtos.Request payload, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                .build();
        var future = http.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = future.get();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new InfinityRerankHttpException(response.statusCode());
            }
            try {
                return json.readValue(response.body(), InfinityRerankDtos.Response.class);
            } catch (IOException malformed) {
                throw new InfinityRerankContractException(malformed);
            }
        } catch (InterruptedException failure) {
            future.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Infinity rerank HTTP request failed", cause);
        }
    }

    static final class InfinityRerankHttpException extends IOException {
        private final int status;

        InfinityRerankHttpException(int status) {
            super("INFINITY_RERANK_HTTP_" + status);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    static final class InfinityRerankContractException extends IOException {
        InfinityRerankContractException(IOException cause) {
            super("RERANK_RESPONSE_MALFORMED", cause);
        }
    }
}
