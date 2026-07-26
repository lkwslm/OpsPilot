package io.github.opspilot.core.port.agent;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Project-owned boundary for chat completion providers. */
public interface ChatPort {

    ChatResponse complete(ChatRequest request);

    default ProviderResult<ChatResponse> invoke(ChatInvocation invocation) {
        ChatResponse response = complete(invocation.request());
        return new ProviderResult<>(response,
                new ProviderUsage(response.usage().inputTokens(), response.usage().outputTokens(), null), null);
    }

    record ChatInvocation(ChatRequest request, Instant deadline, ProviderIdentity identity) {
    }

    record ChatRequest(
            String modelId,
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StructuredOutput structuredOutput) {

        public ChatRequest {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }

        public ChatRequest(String modelId, List<ChatMessage> messages, List<ToolDefinition> tools) {
            this(modelId, messages, tools, null);
        }
    }

    record ChatMessage(String role, String content) {
    }

    record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema) {

        public ToolDefinition {
            inputSchema = Map.copyOf(inputSchema);
        }
    }

    record ToolCall(String id, String name, Map<String, Object> arguments) {

        public ToolCall {
            arguments = Map.copyOf(arguments);
        }
    }

    record TokenUsage(int inputTokens, int outputTokens, int cachedTokens) {
    }

    record ChatResponse(
            String responseId,
            String text,
            List<ToolCall> toolCalls,
            TokenUsage usage,
            String finishReason,
            ProviderIdentity actualIdentity) {

        public ChatResponse {
            toolCalls = List.copyOf(toolCalls);
        }

        public ChatResponse(
                String responseId,
                String text,
                List<ToolCall> toolCalls,
                TokenUsage usage,
                String finishReason) {
            this(responseId, text, toolCalls, usage, finishReason, null);
        }
    }

    /** repairAllowed means the profile permits repair and the caller reserved one repair call in its budget. */
    record StructuredOutput(String name, Map<String, Object> jsonSchema, boolean strict, boolean repairAllowed) {
        public StructuredOutput {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("structured output name must not be blank");
            }
            jsonSchema = Map.copyOf(jsonSchema);
        }
    }
}
