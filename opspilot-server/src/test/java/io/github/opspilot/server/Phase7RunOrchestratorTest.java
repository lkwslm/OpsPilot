package io.github.opspilot.server;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Phase7RunOrchestratorTest {
    @Test
    void preservesStableFinalizationCodeWithoutLeakingArbitraryFailureMessages() {
        assertEquals("PHASE7_HYPOTHESIS_ARTIFACT_INVALID",
                Phase7RunOrchestrator.finalizationErrorCode(
                        new IllegalStateException("PHASE7_HYPOTHESIS_ARTIFACT_INVALID")));
        assertEquals("PRODUCT_RUN_FINALIZATION_FAILED",
                Phase7RunOrchestrator.finalizationErrorCode(
                        new IllegalStateException("password=must-not-leak")));
        assertEquals("PRODUCT_RUN_FINALIZATION_DATABASE_FAILED",
                Phase7RunOrchestrator.finalizationErrorCode(
                        new SQLException("internal database detail", "40001")));
        var failure = new IllegalStateException("secret detail");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("org.postgresql.Driver", "call", "Driver.java", 10),
                new StackTraceElement("io.github.opspilot.server.SafeClass", "safeMethod", "SafeClass.java", 42)
        });
        assertEquals("io.github.opspilot.server.SafeClass#safeMethod:42",
                Phase7RunOrchestrator.failureSite(failure));
    }
}
