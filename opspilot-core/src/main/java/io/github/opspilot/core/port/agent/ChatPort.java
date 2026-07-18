package io.github.opspilot.core.port.agent;

import java.util.List;
import java.util.Map;

/** Project-owned boundary for chat completion providers. */
public interface ChatPort {

    ChatResponse complete(ChatRequest request);

    record ChatRequest(
            String modelId,
            List<ChatMessage> messages,
            List<ToolDefinition> tools) {

        public ChatRequest {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
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
            String finishReason) {

        public ChatResponse {
            toolCalls = List.copyOf(toolCalls);
        }
    }
}
