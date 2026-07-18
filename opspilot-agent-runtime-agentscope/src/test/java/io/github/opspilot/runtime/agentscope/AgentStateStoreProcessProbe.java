package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.state.State;

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
                System.out.println("SAVED:session-a,session-b");
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
                System.out.println("RESTORED:session-a=checkpoint-a,session-b=checkpoint-b;ISOLATED:true");
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
