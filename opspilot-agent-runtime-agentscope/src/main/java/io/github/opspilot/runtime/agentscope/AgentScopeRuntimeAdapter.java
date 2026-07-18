package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.tool.Toolkit;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.ToolPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Prepares an AgentScope runtime without exposing framework types to core. */
public final class AgentScopeRuntimeAdapter {

    public PreparedAgent prepare(
            String agentName,
            String systemPrompt,
            String modelId,
            int maxRounds,
            ChatPort chatPort,
            List<ToolPort> toolPorts) {
        Objects.requireNonNull(chatPort, "chatPort");
        ReactConfig reactConfig = new ReactConfig(maxRounds, true);
        Toolkit toolkit = new Toolkit();
        toolPorts.stream()
                .map(AgentScopeToolAdapter::new)
                .forEach(toolkit::registerAgentTool);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(systemPrompt)
                .model(new AgentScopeChatModelAdapter(modelId, chatPort))
                .toolkit(toolkit)
                .maxIters(reactConfig.maxIters())
                .stopOnReject(reactConfig.stopOnReject());
        return new PreparedAgent(
                agentName, modelId, reactConfig.maxIters(), toolkit.getToolNames(), builder);
    }

    public static final class PreparedAgent {
        private final String agentName;
        private final String modelId;
        private final int maxRounds;
        private final Set<String> toolNames;
        private final ReActAgent.Builder builder;

        private PreparedAgent(
                String agentName,
                String modelId,
                int maxRounds,
                Set<String> toolNames,
                ReActAgent.Builder builder) {
            this.agentName = agentName;
            this.modelId = modelId;
            this.maxRounds = maxRounds;
            this.toolNames = Set.copyOf(toolNames);
            this.builder = builder;
        }

        public String agentName() {
            return agentName;
        }

        public String modelId() {
            return modelId;
        }

        public int maxRounds() {
            return maxRounds;
        }

        public Set<String> toolNames() {
            return toolNames;
        }

        ReActAgent.Builder builder() {
            return builder;
        }
    }
}
