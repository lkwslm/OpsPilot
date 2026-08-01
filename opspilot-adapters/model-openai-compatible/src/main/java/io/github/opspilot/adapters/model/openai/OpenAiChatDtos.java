package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** OpenAI-compatible wire DTOs. Credentials deliberately have no representation here. */
public final class OpenAiChatDtos {
    private OpenAiChatDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompletionRequest(
            String model,
            List<Message> messages,
            List<Tool> tools,
            Boolean stream,
            @JsonProperty("response_format") ResponseFormat responseFormat) {
        public CompletionRequest {
            messages = List.copyOf(messages);
            tools = tools == null || tools.isEmpty() ? null : List.copyOf(tools);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
            String role,
            String content,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId) {
        public Message {
            toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        }

        public Message(String role, String content, List<ToolCall> toolCalls) {
            this(role, content, toolCalls, null);
        }
    }

    public record Tool(String type, FunctionDefinition function) {
    }

    public record FunctionDefinition(String name, String description, Map<String, Object> parameters) {
        public FunctionDefinition {
            parameters = Map.copyOf(parameters);
        }
    }

    public record ToolCall(String id, String type, FunctionCall function) {
    }

    public record FunctionCall(String name, String arguments) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResponseFormat(String type, @JsonProperty("json_schema") JsonSchema jsonSchema) {
    }

    public record JsonSchema(String name, boolean strict, Map<String, Object> schema) {
        public JsonSchema {
            schema = Map.copyOf(schema);
        }
    }

    public record CompletionResponse(String id, String model, List<Choice> choices, Usage usage) {
        public CompletionResponse {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    public record Choice(int index, Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("prompt_cache_hit_tokens") int cachedTokens) {
    }
}
