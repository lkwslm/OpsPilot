package io.github.opspilot.faultlab;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class ToxiproxyControlAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final HttpClient client;

    public ToxiproxyControlAdapter(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid Toxiproxy control endpoint");
        }
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public void addLatency(long latencyMillis, long jitterMillis) {
        if (latencyMillis < 0 || latencyMillis > 30_000 || jitterMillis < 0 || jitterMillis > 5_000) {
            throw new IllegalArgumentException("Latency outside controlled bounds");
        }
        send("POST", "/proxies/inventory-downstream/toxics", Map.of(
                "name", "inventory-latency", "type", "latency", "stream", "downstream",
                "toxicity", 1, "attributes", Map.of("latency", latencyMillis, "jitter", jitterMillis)), 200);
    }

    public void reset() {
        int status = send("DELETE", "/proxies/inventory-downstream/toxics/inventory-latency", null, 204);
        if (status != 204 && status != 404) throw new IllegalStateException("TOXIPROXY_RESET_FAILED");
    }

    private int send(String method, String path, Object body, int expected) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body));
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json").method(method, publisher).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != expected && !("DELETE".equals(method) && status == 404)) {
                throw new IllegalStateException("TOXIPROXY_CONTROL_FAILED");
            }
            return status;
        } catch (IOException exception) {
            throw new IllegalStateException("TOXIPROXY_UNAVAILABLE", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TOXIPROXY_CANCELLED", exception);
        }
    }
}
