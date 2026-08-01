package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.port.agent.ChatPort.TokenUsage;
import io.github.opspilot.core.port.agent.ChatPort.ToolCall;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps project-owned Chat contracts to and from OpenAI-compatible DTOs. */
public final class OpenAiChatMapper {
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public OpenAiChatMapper() {
        this(new ObjectMapper());
    }

    public OpenAiChatMapper(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json").copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public OpenAiChatDtos.CompletionRequest toRequest(ChatRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        List<OpenAiChatDtos.Message> messages = request.messages().stream()
                .map(this::toMessage)
                .toList();
        List<OpenAiChatDtos.Tool> tools = request.tools().stream()
                .map(this::toTool)
                .toList();
        OpenAiChatDtos.ResponseFormat responseFormat = request.structuredOutput() == null ? null
                : new OpenAiChatDtos.ResponseFormat("json_schema",
                        new OpenAiChatDtos.JsonSchema(request.structuredOutput().name(),
                                request.structuredOutput().strict(), request.structuredOutput().jsonSchema()));
        return new OpenAiChatDtos.CompletionRequest(
                request.modelId(), messages, tools, stream, responseFormat);
    }

    public MappedResponse toDomain(OpenAiChatDtos.CompletionResponse response, String providerId, String revision) {
        Objects.requireNonNull(response, "response");
        if (response.choices().size() != 1 || response.choices().getFirst().message() == null) {
            throw new IllegalArgumentException("chat response must contain exactly one message choice");
        }
        OpenAiChatDtos.Choice choice = response.choices().getFirst();
        OpenAiChatDtos.Message message = choice.message();
        List<ToolCall> toolCalls = message.toolCalls() == null ? List.of() : message.toolCalls().stream()
                .map(this::toToolCall)
                .toList();
        OpenAiChatDtos.Usage wireUsage = response.usage();
        TokenUsage usage = wireUsage == null
                ? new TokenUsage(0, 0, 0)
                : new TokenUsage(wireUsage.promptTokens(), wireUsage.completionTokens(), wireUsage.cachedTokens());
        ProviderIdentity actualIdentity = new ProviderIdentity(
                providerId, requireText(response.model(), "response.model"), revision);
        ChatResponse chatResponse = new ChatResponse(response.id(), message.content(), toolCalls, usage,
                choice.finishReason(), actualIdentity);
        return new MappedResponse(chatResponse, actualIdentity);
    }

    public OpenAiChatDtos.CompletionResponse readResponse(String body) {
        try {
            return json.readValue(body, OpenAiChatDtos.CompletionResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid OpenAI-compatible chat response", exception);
        }
    }

    private OpenAiChatDtos.Message toMessage(ChatMessage message) {
        List<OpenAiChatDtos.ToolCall> toolCalls = message.toolCalls().isEmpty() ? null
                : message.toolCalls().stream().map(call -> new OpenAiChatDtos.ToolCall(
                        call.id(), "function", new OpenAiChatDtos.FunctionCall(
                                call.name(), argumentsJson(call.arguments())))).toList();
        return new OpenAiChatDtos.Message(
                message.role(), message.content(), toolCalls, message.toolCallId());
    }

    private String argumentsJson(Map<String, Object> arguments) {
        try {
            return json.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool call arguments cannot be serialized", exception);
        }
    }

    private OpenAiChatDtos.Tool toTool(ToolDefinition tool) {
        return new OpenAiChatDtos.Tool("function",
                new OpenAiChatDtos.FunctionDefinition(tool.name(), tool.description(), tool.inputSchema()));
    }

    private ToolCall toToolCall(OpenAiChatDtos.ToolCall toolCall) {
        if (toolCall.function() == null) {
            throw new IllegalArgumentException("tool call function is required");
        }
        try {
            Map<String, Object> arguments = json.readValue(toolCall.function().arguments(), ARGUMENTS);
            return new ToolCall(toolCall.id(), toolCall.function().name(), arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool call arguments must be a JSON object", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public record MappedResponse(ChatResponse response, ProviderIdentity actualIdentity) {
    }
}
