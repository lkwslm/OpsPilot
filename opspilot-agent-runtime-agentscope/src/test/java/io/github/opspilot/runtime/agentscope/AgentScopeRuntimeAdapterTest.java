package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.ToolPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentScopeRuntimeAdapterTest {

    @Test
    void adaptsProjectChatPortToAgentScopeModelApi() {
        AtomicReference<ChatPort.ChatRequest> captured = new AtomicReference<>();
        ChatPort chatPort = request -> {
            captured.set(request);
            return new ChatPort.ChatResponse(
                    "response-1",
                    "checking metrics",
                    List.of(new ChatPort.ToolCall(
                            "call-1", "MetricQueryTool", Map.of("query", "up"))),
                    new ChatPort.TokenUsage(12, 7, 2),
                    "tool_calls");
        };
        AgentScopeChatModelAdapter model = new AgentScopeChatModelAdapter("chat-fixed", chatPort);
        Msg user = Msg.builderForRole(MsgRole.USER)
                .content(TextBlock.builder().text("diagnose").build())
                .build();
        ToolSchema tool = ToolSchema.builder()
                .name("MetricQueryTool")
                .description("query metrics")
                .parameters(Map.of("type", "object"))
                .build();

        io.agentscope.core.model.ChatResponse response = model
                .stream(List.of(user), List.of(tool), GenerateOptions.builder().build())
                .blockFirst();

        assertNotNull(response);
        assertEquals("chat-fixed", captured.get().modelId());
        assertEquals("diagnose", captured.get().messages().getFirst().content());
        assertEquals("MetricQueryTool", captured.get().tools().getFirst().name());
        assertEquals("checking metrics", ((TextBlock) response.getContent().getFirst()).getText());
        assertEquals("MetricQueryTool", ((ToolUseBlock) response.getContent().get(1)).getName());
    }

    @Test
    void adaptsProjectToolPortToAgentScopeToolApi() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        ToolPort toolPort = new ToolPort() {
            public String name() { return "MetricQueryTool"; }
            public String description() { return "query metrics"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public ToolResult execute(Map<String, Object> input) {
                captured.set(input);
                return new ToolResult(true, "metric result");
            }
        };
        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter(toolPort);
        ToolCallParam param = ToolCallParam.builder()
                .input(Map.of("query", "up"))
                .build();

        ToolResultBlock result = adapter.callAsync(param).block();

        assertNotNull(result);
        assertEquals("up", captured.get().get("query"));
        assertEquals("metric result", ((TextBlock) result.getOutput().getFirst()).getText());
    }

    @Test
    void preparesRealReactBuilderWithoutLeakingFrameworkTypesThroughPublicApi() {
        ChatPort chatPort = request -> new ChatPort.ChatResponse(
                "response-1", "done", List.of(),
                new ChatPort.TokenUsage(1, 1, 0), "stop");
        ToolPort toolPort = new ToolPort() {
            public String name() { return "MetricQueryTool"; }
            public String description() { return "query metrics"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public ToolResult execute(Map<String, Object> input) { return new ToolResult(true, "ok"); }
        };

        AgentScopeRuntimeAdapter.PreparedAgent prepared = new AgentScopeRuntimeAdapter().prepare(
                "diagnosis", "Use evidence only", "chat-fixed", 4, chatPort, List.of(toolPort));

        assertEquals(4, prepared.maxRounds());
        assertEquals(Set.of("MetricQueryTool"), prepared.toolNames());
        assertNotNull(prepared.builder());
        for (Method method : AgentScopeRuntimeAdapter.class.getMethods()) {
            assertFalse(method.getReturnType().getName().startsWith("io.agentscope"));
            assertTrue(List.of(method.getParameterTypes()).stream()
                    .noneMatch(type -> type.getName().startsWith("io.agentscope")));
        }
    }
}
