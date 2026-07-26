package io.github.opspilot.core.application.governance;

import io.github.opspilot.core.application.governance.TokenBudgetManager.LedgerEntry;
import io.github.opspilot.core.port.agent.RuntimeAuditSink.AuditEvent;
import io.github.opspilot.core.port.repository.UsageLedgerRepository.UsageRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Maps usage entries to persistence and bounded audit records without request or response bodies. */
public final class UsageLedgerMapper {
    public UsageRecord toPersistence(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new UsageRecord(entry.invocationId(), entry.scope().incidentId(), entry.scope().taskId(),
                entry.scope().agentId(), entry.inputTokens(), entry.outputTokens(), entry.cachedTokens(),
                entry.costMicros(), entry.costEstimated(), entry.priceTableVersion(), entry.source().name(),
                entry.estimatorVersion(), entry.outcome().name(), entry.recordedAt());
    }

    public AuditEvent toAudit(LedgerEntry entry, long sequence, int round) {
        Objects.requireNonNull(entry, "entry");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("invocationId", entry.invocationId().toString());
        attributes.put("usageSource", entry.source().name());
        attributes.put("cachedTokens", entry.cachedTokens());
        attributes.put("attemptOutcome", entry.outcome().name());
        attributes.put("costAvailable", entry.costMicros() != null);
        attributes.put("costEstimated", entry.costEstimated());
        if (entry.costMicros() != null) {
            attributes.put("costMicros", entry.costMicros());
        }
        if (entry.priceTableVersion() != null) {
            attributes.put("priceTableVersion", entry.priceTableVersion());
        }
        if (entry.estimatorVersion() != null) {
            attributes.put("estimatorVersion", entry.estimatorVersion());
        }
        return new AuditEvent(sequence, "MODEL_USAGE", round, entry.invocationId().toString(),
                Math.toIntExact(entry.inputTokens()), Math.toIntExact(entry.outputTokens()), null,
                entry.outcome() == TokenBudgetManager.AttemptOutcome.CANCELLED, attributes);
    }
}
