package io.github.opspilot.core.governance;

import io.github.opspilot.core.application.governance.TokenBudgetManager.AttemptOutcome;
import io.github.opspilot.core.application.governance.TokenBudgetManager.LedgerEntry;
import io.github.opspilot.core.application.governance.TokenBudgetManager.UsageScope;
import io.github.opspilot.core.application.governance.TokenBudgetManager.UsageSource;
import io.github.opspilot.core.application.governance.UsageLedgerMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UsageLedgerMapperTest {
    @Test
    void mapsCachedFailedEstimatedUsageWithoutBodiesOrInventedCost() {
        UUID invocationId = UUID.randomUUID();
        LedgerEntry entry = new LedgerEntry(invocationId,
                new UsageScope("incident-1", "task-1", "agent-1"),
                20, 10, 4, null, false, null, UsageSource.CONSERVATIVE_ESTIMATE,
                "estimate-v1", AttemptOutcome.FAILED, Instant.parse("2026-07-26T00:00:00Z"));
        UsageLedgerMapper mapper = new UsageLedgerMapper();

        var record = mapper.toPersistence(entry);
        var audit = mapper.toAudit(entry, 7, 2);

        assertEquals(invocationId, record.invocationId());
        assertEquals(4, record.cachedTokens());
        assertEquals("FAILED", record.attemptOutcome());
        assertEquals("estimate-v1", record.estimatorVersion());
        assertEquals("MODEL_USAGE", audit.type());
        assertEquals(false, audit.attributes().get("costAvailable"));
        assertFalse(audit.attributes().containsKey("prompt"));
        assertFalse(audit.attributes().containsKey("response"));
    }
}
