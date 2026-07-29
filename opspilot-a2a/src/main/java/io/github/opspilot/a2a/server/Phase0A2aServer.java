package io.github.opspilot.a2a.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.a2a.contract.A2aJson;
import io.github.opspilot.a2a.contract.A2aCapabilityPolicy;
import io.github.opspilot.a2a.contract.A2aProtocol;
import io.github.opspilot.a2a.contract.A2aProtocolException;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskEvent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Minimal HTTP+JSON server for the five Phase 0 A2A operations. */
public final class Phase0A2aServer implements AutoCloseable {

    private static final String ROOT = "/a2a";

    private final PostgresA2aTaskStore store;
    private final HttpServer server;
    private final Consumer<String> auditSink;
    private final A2aCapabilityPolicy capabilityPolicy;

    public Phase0A2aServer(PostgresA2aTaskStore store, int port) {
        this(store, port, ignored -> { }, A2aCapabilityPolicy.supervisor());
    }

    public Phase0A2aServer(PostgresA2aTaskStore store, int port, Consumer<String> auditSink) {
        this(store, port, auditSink, A2aCapabilityPolicy.supervisor());
    }

    public Phase0A2aServer(
            PostgresA2aTaskStore store,
            int port,
            Consumer<String> auditSink,
            A2aCapabilityPolicy capabilityPolicy) {
        this.store = store;
        this.auditSink = auditSink;
        this.capabilityPolicy = capabilityPolicy;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to bind A2A server", exception);
        }
        server.createContext(ROOT, this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        auditSink.accept("HTTP_EXCHANGE processId=" + ProcessHandle.current().pid()
                + " method=" + exchange.getRequestMethod()
                + " path=" + exchange.getRequestURI().getPath()
                + " contentType=" + exchange.getRequestHeaders().getFirst("Content-Type")
                + " a2aVersion=" + exchange.getRequestHeaders().getFirst(A2aProtocol.VERSION_HEADER));
        try {
            validateBinding(exchange);
            capabilityPolicy.validateCaller(
                    exchange.getRequestHeaders().getFirst(A2aProtocol.SERVICE_ID_HEADER));
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(ROOT + "/messages:send")) {
                A2aTask task = process(validatedRequest(exchange));
                writeJson(exchange, 200, task);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(ROOT + "/messages:stream")) {
                A2aTask task = process(validatedRequest(exchange));
                writeEvents(exchange, store.eventsAfter(task.taskId(), 0));
                return;
            }
            if (path.startsWith(ROOT + "/tasks/")) {
                handleTask(exchange, path.substring((ROOT + "/tasks/").length()));
                return;
            }
            writeJson(exchange, 404, new ErrorResponse("NOT_FOUND"));
        } catch (IllegalArgumentException exception) {
            writeJson(exchange, 404, new ErrorResponse(exception.getMessage()));
        } catch (A2aProtocolException exception) {
            writeJson(exchange, exception.status(), new ErrorResponse(exception.code()));
        } catch (IllegalStateException exception) {
            writeJson(exchange, 409, new ErrorResponse(exception.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleTask(HttpExchange exchange, String operation) throws IOException {
        if ("GET".equals(exchange.getRequestMethod()) && operation.endsWith(":subscribe")) {
            String taskId = operation.substring(0, operation.length() - ":subscribe".length());
            writeEvents(exchange, store.eventsAfter(taskId, after(exchange)));
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && operation.endsWith(":cancel")) {
            String taskId = operation.substring(0, operation.length() - ":cancel".length());
            writeJson(exchange, 200, store.cancel(taskId));
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && !operation.contains(":")) {
            A2aTask task = store.get(operation)
                    .orElseThrow(() -> new IllegalArgumentException("TASK_NOT_FOUND:" + operation));
            writeJson(exchange, 200, task);
            return;
        }
        writeJson(exchange, 404, new ErrorResponse("NOT_FOUND"));
    }

    private A2aTask process(A2aSendRequest request) {
        PostgresA2aTaskStore.CreateResult created = store.create(request);
        A2aTask task = created.task();
        if (!created.created()) {
            return task;
        }
        if (request.deferCompletion()) {
            return task;
        }
        store.markWorking(task.taskId());
        return store.complete(task.taskId(), request.text(), request.outputMediaType());
    }

    private A2aSendRequest validatedRequest(HttpExchange exchange) throws IOException {
        A2aSendRequest request = readRequest(exchange);
        capabilityPolicy.validate(request,
                exchange.getRequestHeaders().getFirst(A2aProtocol.SERVICE_ID_HEADER));
        return request;
    }

    private static void validateBinding(HttpExchange exchange) {
        String version = exchange.getRequestHeaders().getFirst(A2aProtocol.VERSION_HEADER);
        if (!A2aProtocol.VERSION.equals(version)) {
            throw new A2aProtocolException(426, "A2A_VERSION_NOT_SUPPORTED");
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().startsWith(A2aProtocol.MEDIA_TYPE)) {
                throw new A2aProtocolException(415, "A2A_CONTENT_TYPE_NOT_SUPPORTED");
            }
        }
    }

    private static A2aSendRequest readRequest(HttpExchange exchange) throws IOException {
        String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return A2aJson.read(json, A2aSendRequest.class);
    }

    private static long after(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || !query.startsWith("after=")) {
            return 0L;
        }
        return Long.parseLong(query.substring("after=".length()));
    }

    private static void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = A2aJson.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", A2aProtocol.MEDIA_TYPE + "; charset=utf-8");
        exchange.getResponseHeaders().set(A2aProtocol.VERSION_HEADER, A2aProtocol.VERSION);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void writeEvents(HttpExchange exchange, List<A2aTaskEvent> events) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        for (A2aTaskEvent event : events) {
            exchange.getResponseBody().write(
                    (A2aJson.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }
    }

    private record ErrorResponse(String error) {
    }
}
