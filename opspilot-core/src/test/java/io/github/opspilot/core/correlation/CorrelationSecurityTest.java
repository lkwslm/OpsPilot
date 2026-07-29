package io.github.opspilot.core.correlation;

import io.github.opspilot.core.application.correlation.CorrelationContext;
import io.github.opspilot.core.application.correlation.CorrelationBoundaryMapper;
import io.github.opspilot.core.application.correlation.StableErrorSanitizer;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationSecurityTest {
    @Test
    void authorityChainIsServerOwnedAndCannotBeOverridden() {
        CorrelationContext ingress = CorrelationContext.ingress("tenant-a");
        CorrelationContext secondIngress = CorrelationContext.ingress("tenant-a");
        assertNotEquals(ingress.requestId(), secondIngress.requestId());
        assertNotEquals(ingress.traceId(), secondIngress.traceId());

        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        CorrelationContext correlated = ingress.withIncident(incidentId).withRun(runId)
                .withStep(stepId).withA2aTask("task-01").withInvocation(invocationId);

        assertEquals(incidentId.toString(), correlated.propagationHeaders().get("X-Incident-Id"));
        assertEquals(runId.toString(), correlated.propagationHeaders().get("X-Run-Id"));
        assertEquals("task-01", correlated.propagationHeaders().get("X-A2A-Task-Id"));
        assertThrows(CorrelationContext.AuthorityConflict.class,
                () -> correlated.withRun(UUID.randomUUID()));
        assertThrows(IllegalStateException.class,
                () -> ingress.withInvocation(UUID.randomUUID()));

        assertEquals(correlated.requestId().toString(),
                CorrelationBoundaryMapper.mdc(correlated).get("request.id"));
        assertEquals(correlated.traceId().toString(),
                CorrelationBoundaryMapper.telemetryAttributes(correlated).get("opspilot.trace.id"));
        assertEquals(correlated, CorrelationBoundaryMapper.restore(correlated,
                CorrelationBoundaryMapper.httpHeaders(correlated)));
        HashMap<String, Object> forged = new HashMap<>(
                CorrelationBoundaryMapper.a2aMetadata(correlated));
        forged.put("X-Run-Id", UUID.randomUUID().toString());
        assertThrows(CorrelationContext.AuthorityConflict.class,
                () -> CorrelationBoundaryMapper.restore(correlated, forged));
    }

    @Test
    void stableProjectionRedactsSecretsQueriesPromptsAndStacks() {
        String raw = "authorization=Bearer-secret Bearer second-secret https://service/path?token=abc "
                + "prompt=private payload\n at example.Type.method(Type.java:42)";
        StableErrorSanitizer.Projection projection = StableErrorSanitizer.project(raw);

        assertTrue(projection.artifactRequired());
        assertTrue(projection.summary().contains("[REDACTED]"));
        assertFalse(projection.summary().contains("Bearer-secret"));
        assertFalse(projection.summary().contains("second-secret"));
        assertFalse(projection.summary().contains("token=abc"));
        assertFalse(projection.summary().contains("private payload"));
        assertFalse(projection.summary().contains("Type.java"));
        assertTrue(projection.summary().length() <= 256);
    }
}
