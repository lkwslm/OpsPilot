package io.github.opspilot.core.port.repository;

import java.time.Instant;
import java.util.UUID;

/** Append-only persistence boundary for reconciled model usage attempts. */
public interface UsageLedgerRepository {
    void append(UUID runId, UUID modelRevisionId, UsageRecord record);

    record UsageRecord(
            UUID invocationId,
            String incidentId,
            String taskId,
            String agentId,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            Long costMicros,
            boolean costEstimated,
            String priceTableVersion,
            String usageSource,
            String estimatorVersion,
            String attemptOutcome,
            Instant recordedAt) {
    }
}
