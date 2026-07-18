package io.github.opspilot.runtime.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.opspilot.core.port.agent.ChatPort;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

final class AgentScopeChatModelAdapter implements Model {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String modelId;
    private final ChatPort chatPort;

    AgentScopeChatModelAdapter(String modelId, ChatPort chatPort) {
        this.modelId = modelId;
        this.chatPort = chatPort;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        return Flux.defer(() -> {
            ChatPort.ChatRequest request = new ChatPort.ChatRequest(
                    modelId,
                    messages.stream()
                            .map(message -> new ChatPort.ChatMessage(
                                    message.getRole().name(), message.getTextContent()))
                            .toList(),
                    tools.stream()
                            .map(tool -> new ChatPort.ToolDefinition(
                                    tool.getName(), tool.getDescription(), tool.getParameters()))
                            .toList());
            return Flux.just(toAgentScopeResponse(chatPort.complete(request)));
        });
    }

    @Override
    public String getModelName() {
        return modelId;
    }

    private ChatResponse toAgentScopeResponse(ChatPort.ChatResponse response) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (response.text() != null && !response.text().isBlank()) {
            blocks.add(TextBlock.builder().text(response.text()).build());
        }
        response.toolCalls().stream()
                .map(call -> ToolUseBlock.builder()
                        .id(call.id())
                        .name(call.name())
                        .input(call.arguments())
                        .content(toolArguments(call))
                        .build())
                .forEach(blocks::add);
        ChatPort.TokenUsage usage = response.usage();
        return ChatResponse.builder()
                .id(response.responseId())
                .content(blocks)
                .usage(ChatUsage.builder()
                        .inputTokens(usage.inputTokens())
                        .outputTokens(usage.outputTokens())
                        .cachedTokens(usage.cachedTokens())
                        .build())
                .finishReason(response.finishReason())
                .build();
    }

    private String toolArguments(ChatPort.ToolCall call) {
        try {
            return objectMapper.writeValueAsString(call.arguments());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", error);
        }
    }
}
