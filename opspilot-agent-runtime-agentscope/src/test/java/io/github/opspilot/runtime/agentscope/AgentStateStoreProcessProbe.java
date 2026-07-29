package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.state.State;
import io.github.opspilot.core.port.agent.AgentExecutionService.ExecutionUsage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Child-JVM probe used to prove state survives a process boundary. */
public final class AgentStateStoreProcessProbe {

    private AgentStateStoreProcessProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected mode, JDBC URL, username, password, and serverAgentId");
        }
        String mode = args[0];
        try (PostgresAgentStateStore store = new PostgresAgentStateStore(
                args[4], args[1], args[2], args[3])) {
            if ("save".equals(mode)) {
                store.save("user-1", "session-a", "checkpoint", new CheckpointState("checkpoint-a", 3));
                store.save("user-1", "session-b", "checkpoint", new CheckpointState("checkpoint-b", 7));
                store.save("opspilot-system", "diagnosis:task-1", "opspilot.execution.checkpoint",
                        new AgentScopeExecutionService.ExecutionJournalState(
                                "sha256:journal", "execution-1", "diagnosis", "diagnosis:task-1",
                                1, "COMPLETED", null, "bounded decision",
                                new ExecutionUsage(2, 2, 1, 20, 5, 0),
                                List.of(new AgentScopeExecutionService.JournalEvent(
                                        1, "MODEL_COMPLETED", null, Map.of("status", "SUCCEEDED"))),
                                Instant.parse("2026-07-28T00:00:00Z")));
                System.out.println("SAVED:session-a,session-b,journal");
                return;
            }
            if ("verify".equals(mode)) {
                CheckpointState first = required(store.get(
                        "user-1", "session-a", "checkpoint", CheckpointState.class));
                CheckpointState second = required(store.get(
                        "user-1", "session-b", "checkpoint", CheckpointState.class));
                if (!first.equals(new CheckpointState("checkpoint-a", 3))
                        || !second.equals(new CheckpointState("checkpoint-b", 7))
                        || first.equals(second)) {
                    throw new IllegalStateException("Session checkpoints crossed or changed");
                }
                PostgresAgentStateStore otherAgent = new PostgresAgentStateStore(
                        "server-agent-other", args[1], args[2], args[3]);
                if (otherAgent.exists("user-1", "session-a")) {
                    throw new IllegalStateException("serverAgentId isolation failed");
                }
                AgentScopeExecutionService.ExecutionJournalState journal = required(store.get(
                        "opspilot-system", "diagnosis:task-1", "opspilot.execution.checkpoint",
                        AgentScopeExecutionService.ExecutionJournalState.class));
                if (!"bounded decision".equals(journal.decisionSummary())
                        || journal.usage().totalTokens() != 25
                        || journal.events().size() != 1) {
                    throw new IllegalStateException("Execution journal changed after restart");
                }
                System.out.println("RESTORED:session-a=checkpoint-a,session-b=checkpoint-b;"
                        + "JOURNAL:usage-and-events;ISOLATED:true");
                return;
            }
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static <T> T required(Optional<T> value) {
        return value.orElseThrow(() -> new IllegalStateException("Checkpoint missing after restart"));
    }

    public record CheckpointState(String checkpointId, int round) implements State {
    }
}
