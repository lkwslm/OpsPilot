package io.github.opspilot.runtime.agentscope;

final class AgentExecutionStoppedException extends RuntimeException {
    AgentExecutionStoppedException(String reasonCode) {
        super(reasonCode, null, false, false);
    }
}
