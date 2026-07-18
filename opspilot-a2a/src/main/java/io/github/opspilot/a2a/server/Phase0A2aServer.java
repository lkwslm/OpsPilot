package io.github.opspilot.a2a.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.a2a.contract.A2aJson;
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

    public Phase0A2aServer(PostgresA2aTaskStore store, int port) {
        this(store, port, ignored -> { });
    }

    public Phase0A2aServer(PostgresA2aTaskStore store, int port, Consumer<String> auditSink) {
        this.store = store;
        this.auditSink = auditSink;
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
                + " contentType=" + exchange.getRequestHeaders().getFirst("Content-Type"));
        try {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(ROOT + "/messages:send")) {
                A2aTask task = process(readRequest(exchange));
                writeJson(exchange, 200, task);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.equals(ROOT + "/messages:stream")) {
                A2aTask task = process(readRequest(exchange));
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
        return store.complete(task.taskId(), request.text());
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
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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
