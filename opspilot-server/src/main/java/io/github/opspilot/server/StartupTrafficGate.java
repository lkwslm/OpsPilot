package io.github.opspilot.server;

import java.util.EnumSet;
import java.util.Map;

/** Enforces the frozen startup sequence while keeping liveness independent. */
public final class StartupTrafficGate {
    public enum Stage {
        MACHINE_CONTRACTS,
        IDENTITY_AND_SECRETS,
        PROVIDER_TOOL_SKILL_PROBES,
        CARD_DIRECTORY_RECONCILIATION,
        REGISTRIES_FROZEN
    }

    public enum CapabilityState { UP, DOWN }

    private final EnumSet<Stage> complete = EnumSet.noneOf(Stage.class);

    public synchronized void complete(Stage stage) {
        int expected = complete.size();
        if (stage.ordinal() != expected) {
            throw new IllegalStateException("STARTUP_SEQUENCE_INVALID");
        }
        complete.add(stage);
    }

    public synchronized boolean live() {
        return true;
    }

    public synchronized boolean ready(Map<String, CapabilityState> requiredCapabilities) {
        return complete.size() == Stage.values().length
                && !requiredCapabilities.isEmpty()
                && requiredCapabilities.values().stream().allMatch(state -> state == CapabilityState.UP);
    }

    public synchronized void requireTaskAcceptance(Map<String, CapabilityState> requiredCapabilities) {
        if (!ready(requiredCapabilities)) {
            throw new IllegalStateException("SERVICE_NOT_READY");
        }
    }
}
