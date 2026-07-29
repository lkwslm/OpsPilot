package io.github.opspilot.core.architecture;

import io.github.opspilot.core.port.agent.AgentExecutionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AgentExecutionServiceContractTest {

    @Test
    void derivesStableSupervisorAndSpecialistSessionKeys() {
        AgentExecutionService.ExecutionSession supervisor =
                AgentExecutionService.ExecutionSession.supervisor("run-1", 1, false);
        AgentExecutionService.ExecutionSession continuation =
                AgentExecutionService.ExecutionSession.specialist("diagnosis", "task-1", 1, true);
        AgentExecutionService.ExecutionSession retry =
                AgentExecutionService.ExecutionSession.specialist("diagnosis", "task-2", 2, false);

        assertEquals("opspilot-system", supervisor.userId());
        assertEquals("supervisor:run-1", supervisor.sessionId());
        assertEquals("diagnosis:task-1", continuation.sessionId());
        assertEquals("diagnosis:task-2", retry.sessionId());
        assertFalse(continuation.sessionId().equals(retry.sessionId()));
    }

    @Test
    void rejectsNonMvpUserAndInvalidLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentExecutionService.ExecutionSession("user", "session", 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentExecutionService.ExecutionLimits(1, 1, 0, 0, 1, Instant.now()));
    }

    @Test
    void contractContainsOnlyProjectAndJdkTypes() {
        Arrays.stream(AgentExecutionService.class.getDeclaredClasses())
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(field -> field.getType().getName())
                .forEach(type -> {
                    assertFalse(type.startsWith("io.agentscope"), type);
                    assertFalse(type.startsWith("org.springframework"), type);
                    assertFalse(type.startsWith("org.a2aproject"), type);
                });
    }
}
