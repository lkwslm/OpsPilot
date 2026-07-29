package io.github.opspilot.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StartupTrafficGateTest {
    @Test
    void livenessStaysUpWhileReadinessAndTasksWaitForOrderedStartup() {
        var gate = new StartupTrafficGate();
        Map<String, StartupTrafficGate.CapabilityState> up = Map.of(
                "database", StartupTrafficGate.CapabilityState.UP,
                "skill", StartupTrafficGate.CapabilityState.UP);

        assertTrue(gate.live());
        assertFalse(gate.ready(up));
        assertThrows(IllegalStateException.class, () -> gate.requireTaskAcceptance(up));
        assertThrows(IllegalStateException.class,
                () -> gate.complete(StartupTrafficGate.Stage.IDENTITY_AND_SECRETS));

        for (StartupTrafficGate.Stage stage : StartupTrafficGate.Stage.values()) {
            gate.complete(stage);
        }
        assertTrue(gate.ready(up));
        assertFalse(gate.ready(Map.of("database", StartupTrafficGate.CapabilityState.DOWN)));
        assertTrue(gate.live());
    }
}
