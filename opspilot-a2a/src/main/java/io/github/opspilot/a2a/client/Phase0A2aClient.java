package io.github.opspilot.a2a.client;

import io.github.opspilot.a2a.contract.A2aJson;
import io.github.opspilot.a2a.contract.A2aProtocol;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;

/** HTTP+JSON client exposing send, stream, get, cancel, and subscribe. */
public final class Phase0A2aClient implements AutoCloseable {

    private final URI endpoint;
    private final HttpClient httpClient;
    private final String callerServiceId;

    public Phase0A2aClient(URI endpoint) {
        this(endpoint, "opspilot-server");
    }

    public Phase0A2aClient(URI endpoint, String callerServiceId) {
        this.endpoint = endpoint;
        this.callerServiceId = callerServiceId;
        this.httpClient = HttpClient.newHttpClient();
    }

    public A2aTask send(A2aSendRequest request) {
        return task(post("/messages:send", request));
    }

    public List<A2aTaskEvent> stream(A2aSendRequest request, int maximumEvents) {
        HttpRequest httpRequest = request("/messages:stream", request.callerServiceId())
                .POST(HttpRequest.BodyPublishers.ofString(A2aJson.write(request)))
                .build();
        return events(httpRequest, maximumEvents);
    }

    public A2aTask get(String taskId) {
        HttpRequest request = request("/tasks/" + taskId, callerServiceId).GET().build();
        return task(send(request));
    }

    public A2aTask cancel(String taskId) {
        HttpRequest request = request("/tasks/" + taskId + ":cancel", callerServiceId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return task(send(request));
    }

    public List<A2aTaskEvent> subscribe(String taskId, long afterSequence) {
        HttpRequest request = request("/tasks/" + taskId + ":subscribe?after=" + afterSequence,
                callerServiceId)
                .GET()
                .build();
        return events(request, Integer.MAX_VALUE);
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private HttpResponse<String> post(String path, Object body) {
        String requestCaller = body instanceof A2aSendRequest sendRequest
                ? sendRequest.callerServiceId() : callerServiceId;
        HttpRequest request = request(path, requestCaller)
                .POST(HttpRequest.BodyPublishers.ofString(A2aJson.write(body)))
                .build();
        return send(request);
    }

    private HttpRequest.Builder request(String path, String serviceId) {
        return HttpRequest.newBuilder(endpoint.resolve("a2a" + path))
                .header("Accept", A2aProtocol.MEDIA_TYPE + ", application/x-ndjson")
                .header("Content-Type", A2aProtocol.MEDIA_TYPE)
                .header(A2aProtocol.VERSION_HEADER, A2aProtocol.VERSION)
                .header(A2aProtocol.SERVICE_ID_HEADER, serviceId);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("A2A_NETWORK_FAILURE", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A_INTERRUPTED", exception);
        }
    }

    private List<A2aTaskEvent> events(HttpRequest request, int maximumEvents) {
        try {
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            requireSuccess(response.statusCode(), "");
            try (Stream<String> lines = response.body()) {
                return lines.limit(maximumEvents)
                        .map(line -> A2aJson.read(line, A2aTaskEvent.class))
                        .toList();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("A2A_NETWORK_FAILURE", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A_INTERRUPTED", exception);
        }
    }

    private static A2aTask task(HttpResponse<String> response) {
        requireSuccess(response.statusCode(), response.body());
        return A2aJson.read(response.body(), A2aTask.class);
    }

    private static void requireSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("A2A_HTTP_" + statusCode + ":" + body);
        }
    }
}
