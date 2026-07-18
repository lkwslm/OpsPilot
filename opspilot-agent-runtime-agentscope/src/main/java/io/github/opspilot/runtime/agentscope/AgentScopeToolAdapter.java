package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.github.opspilot.core.port.agent.ToolPort;
import reactor.core.publisher.Mono;

import java.util.Map;

final class AgentScopeToolAdapter implements AgentTool {

    private final ToolPort toolPort;

    AgentScopeToolAdapter(ToolPort toolPort) {
        this.toolPort = toolPort;
    }

    @Override
    public String getName() {
        return toolPort.name();
    }

    @Override
    public String getDescription() {
        return toolPort.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return toolPort.inputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromSupplier(() -> {
            ToolPort.ToolResult result = toolPort.execute(param.getInput());
            return result.success()
                    ? ToolResultBlock.text(result.content())
                    : ToolResultBlock.error(result.content());
        });
    }
}
