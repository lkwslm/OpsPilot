package io.github.opspilot.server;

import com.sun.net.httpserver.HttpExchange;
import io.github.opspilot.a2a.contract.A2aJson;
import io.github.opspilot.a2a.contract.A2aProtocol;
import io.github.opspilot.a2a.contract.A2aSendRequest;
import io.github.opspilot.a2a.contract.A2aTask;
import io.github.opspilot.a2a.contract.A2aTaskState;
import io.github.opspilot.a2a.server.PostgresA2aTaskStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Executes a professional role over the frozen HTTP+JSON A2A contract. */
final class Phase7ProfessionalA2aHandler {
    private final String agentId;
    private final String skillId;
    private final PostgresA2aTaskStore tasks;
    private final Phase7AgentExecutor executor;
    private final Phase7EvidenceCollector evidenceCollector;
    private final Phase7CodeAnalyzer codeAnalyzer;
    private final Phase7KnowledgeRetriever knowledgeRetriever;

    Phase7ProfessionalA2aHandler(
            String agentId, String skillId, PostgresA2aTaskStore tasks,
            Phase7AgentExecutor executor, Phase7EvidenceCollector evidenceCollector,
            Phase7CodeAnalyzer codeAnalyzer, Phase7KnowledgeRetriever knowledgeRetriever) {
        this.agentId = required(agentId, "agentId");
        this.skillId = required(skillId, "skillId");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.evidenceCollector = evidenceCollector;
        this.codeAnalyzer = codeAnalyzer;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    void handle(HttpExchange exchange) throws IOException {
        if (!A2aProtocol.MEDIA_TYPE.equals(baseContentType(
                exchange.getRequestHeaders().getFirst("Content-Type")))) {
            write(exchange, 415, "{\"error\":\"A2A_CONTENT_TYPE_NOT_SUPPORTED\"}");
            return;
        }
        if (!A2aProtocol.VERSION.equals(
                exchange.getRequestHeaders().getFirst(A2aProtocol.VERSION_HEADER))) {
            write(exchange, 426, "{\"error\":\"A2A_VERSION_NOT_SUPPORTED\"}");
            return;
        }
        A2aSendRequest request;
        try {
            request = A2aJson.read(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    A2aSendRequest.class);
            validate(request);
        } catch (RuntimeException invalid) {
            write(exchange, 400, "{\"error\":\"A2A_REQUEST_INVALID\"}");
            return;
        }
        PostgresA2aTaskStore.CreateResult created = tasks.create(request);
        if (!created.created() || request.deferCompletion()) {
            write(exchange, 200, A2aJson.write(created.task()));
            return;
        }
        tasks.markWorking(created.task().taskId());
        try {
            CapabilityInvocation capability = capability(request, created.task().taskId());
            String artifact = executor.execute(
                    created.task().taskId(), request.runId(), incidentId(request.text()),
                    request.text(), capability.tools());
            artifact = Phase7DiagnosisNormalizer.normalize(agentId, request.text(), artifact);
            if (capability.result() != null) {
                var collected = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(capability.result().requireExecuted());
                ((com.fasterxml.jackson.databind.node.ObjectNode) collected).set(
                        "agentResult", new com.fasterxml.jackson.databind.ObjectMapper().readTree(artifact));
                artifact = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(collected);
            }
            A2aTask completed = tasks.completeArtifact(
                    created.task().taskId(), artifact, request.outputMediaType(), List.of());
            write(exchange, 200, A2aJson.write(completed));
        } catch (Exception failure) {
            tasks.fail(created.task().taskId());
            System.err.printf("A2A_AGENT_FAILURE agentId=%s type=%s detail=%s%n",
                    agentId, failure.getClass().getSimpleName(), diagnostic(failure));
            write(exchange, 500, "{\"error\":\"AGENT_EXECUTION_FAILED\"}");
        }
    }

    private CapabilityInvocation capability(A2aSendRequest request, String taskId) {
        Phase7CapabilityTool.LazyResult result;
        List<io.github.opspilot.core.port.agent.ToolPort> tools = new ArrayList<>();
        if (evidenceCollector != null) {
            result = new Phase7CapabilityTool.LazyResult(() -> evidenceCollector.collect(
                    request.text(), UUID.fromString(taskId)));
            for (String tool : List.of("LogQueryTool", "MetricQueryTool", "TraceQueryTool",
                    "HealthQueryTool", "TopologyQueryTool", "ConfigReadTool")) {
                tools.add(new Phase7CapabilityTool(tool, result));
            }
            return new CapabilityInvocation(result, List.copyOf(tools));
        }
        if (codeAnalyzer != null) {
            result = new Phase7CapabilityTool.LazyResult(() -> codeAnalyzer.analyze(request.text()));
            return new CapabilityInvocation(result, List.of(new Phase7CapabilityTool("CodeSearchTool", result)));
        }
        if (knowledgeRetriever != null) {
            result = new Phase7CapabilityTool.LazyResult(() -> knowledgeRetriever.search(
                    request.text(), UUID.fromString(request.stepId())));
            return new CapabilityInvocation(
                    result, List.of(new Phase7CapabilityTool("KnowledgeSearchTool", result)));
        }
        return new CapabilityInvocation(null, List.of());
    }

    void handleTask(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"error\":\"A2A_METHOD_NOT_ALLOWED\"}");
            return;
        }
        String prefix = "/a2a/tasks/";
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
            write(exchange, 404, "{\"error\":\"A2A_TASK_NOT_FOUND\"}");
            return;
        }
        String taskId = path.substring(prefix.length());
        try { UUID.fromString(taskId); }
        catch (RuntimeException invalid) {
            write(exchange, 400, "{\"error\":\"A2A_TASK_ID_INVALID\"}");
            return;
        }
        var task = tasks.get(taskId);
        write(exchange, task.isPresent() ? 200 : 404,
                task.map(A2aJson::write).orElse("{\"error\":\"A2A_TASK_NOT_FOUND\"}"));
    }

    private void validate(A2aSendRequest request) {
        if (!"svc:opspilot-server".equals(request.callerServiceId())
                || !agentId.equals(request.targetAgentId())
                || !skillId.equals(request.skillId())
                || !A2aProtocol.VERSION.equals(request.protocolVersion())
                || !request.requiredExtensions().contains(A2aProtocol.CORRELATION_EXTENSION)
                || request.runId() == null || request.stepId() == null
                || request.requestId() == null || request.traceId() == null
                || request.invocationId() == null) {
            throw new IllegalArgumentException("A2A_REQUEST_POLICY_INVALID");
        }
    }

    private static String incidentId(String requestText) throws IOException {
        String value = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestText).path("incidentId").asText();
        return required(value, "incidentId");
    }

    private static String baseContentType(String contentType) {
        if (contentType == null) return "";
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).strip();
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", A2aProtocol.MEDIA_TYPE + "; charset=utf-8");
        exchange.getResponseHeaders().set(A2aProtocol.VERSION_HEADER, A2aProtocol.VERSION);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String diagnostic(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        String normalized = message.replaceAll("[\\r\\n]+", " ");
        return normalized.substring(0, Math.min(normalized.length(), 240));
    }

    private record CapabilityInvocation(
            Phase7CapabilityTool.LazyResult result,
            List<io.github.opspilot.core.port.agent.ToolPort> tools) { }
}
