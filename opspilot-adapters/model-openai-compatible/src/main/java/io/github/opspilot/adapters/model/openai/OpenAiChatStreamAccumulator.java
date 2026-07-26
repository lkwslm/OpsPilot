package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Materializes a domain-mappable response only after a complete SSE sequence. */
final class OpenAiChatStreamAccumulator {
    private final ObjectMapper json;

    OpenAiChatStreamAccumulator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    OpenAiChatDtos.CompletionResponse complete(String eventStream) {
        String responseId = null;
        String model = null;
        String finishReason = null;
        OpenAiChatDtos.Usage usage = null;
        StringBuilder content = new StringBuilder();
        Map<Integer, MutableToolCall> toolCalls = new LinkedHashMap<>();
        boolean done = false;

        for (String line : eventStream.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if ("[DONE]".equals(data)) {
                done = true;
                continue;
            }
            JsonNode chunk = readChunk(data);
            responseId = textOr(responseId, chunk.path("id"));
            model = textOr(model, chunk.path("model"));
            JsonNode usageNode = chunk.path("usage");
            if (usageNode.isObject()) {
                usage = new OpenAiChatDtos.Usage(
                        usageNode.path("prompt_tokens").asInt(),
                        usageNode.path("completion_tokens").asInt(),
                        usageNode.path("prompt_cache_hit_tokens").asInt());
            }
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);
            if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
                finishReason = choice.path("finish_reason").asText();
            }
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isTextual()) {
                content.append(delta.path("content").asText());
            }
            JsonNode calls = delta.path("tool_calls");
            if (calls.isArray()) {
                calls.forEach(call -> appendToolCall(toolCalls, call));
            }
        }
        if (!done || finishReason == null || responseId == null || model == null) {
            throw new IncompleteStreamException("stream ended before a complete terminal event");
        }
        List<OpenAiChatDtos.ToolCall> completedCalls = toolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getValue().complete())
                .toList();
        OpenAiChatDtos.Message message = new OpenAiChatDtos.Message(
                "assistant", content.isEmpty() ? null : content.toString(), completedCalls);
        return new OpenAiChatDtos.CompletionResponse(responseId, model,
                List.of(new OpenAiChatDtos.Choice(0, message, finishReason)), usage);
    }

    private JsonNode readChunk(String data) {
        try {
            return json.readTree(data);
        } catch (JsonProcessingException exception) {
            throw new IncompleteStreamException("stream contained invalid JSON");
        }
    }

    private static String textOr(String current, JsonNode candidate) {
        return candidate.isTextual() && !candidate.asText().isBlank() ? candidate.asText() : current;
    }

    private static void appendToolCall(Map<Integer, MutableToolCall> calls, JsonNode chunk) {
        int index = chunk.path("index").asInt(-1);
        if (index < 0) {
            throw new IncompleteStreamException("tool call chunk has no valid index");
        }
        MutableToolCall call = calls.computeIfAbsent(index, ignored -> new MutableToolCall());
        call.setId(chunk.path("id"));
        JsonNode function = chunk.path("function");
        call.setName(function.path("name"));
        if (function.path("arguments").isTextual()) {
            call.arguments.append(function.path("arguments").asText());
        }
    }

    private static final class MutableToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void setId(JsonNode value) {
            if (value.isTextual()) {
                id = value.asText();
            }
        }

        void setName(JsonNode value) {
            if (value.isTextual()) {
                name = value.asText();
            }
        }

        OpenAiChatDtos.ToolCall complete() {
            if (id == null || name == null || arguments.isEmpty()) {
                throw new IncompleteStreamException("tool call stream ended before its arguments completed");
            }
            return new OpenAiChatDtos.ToolCall(id, "function",
                    new OpenAiChatDtos.FunctionCall(name, arguments.toString()));
        }
    }

    static final class IncompleteStreamException extends RuntimeException {
        IncompleteStreamException(String message) {
            super(message);
        }
    }
}
