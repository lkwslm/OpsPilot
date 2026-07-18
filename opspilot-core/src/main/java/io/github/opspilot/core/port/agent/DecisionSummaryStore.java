package io.github.opspilot.core.port.agent;

import java.util.List;

/** Persists an auditable decision projection without model reasoning or raw responses. */
public interface DecisionSummaryStore {

    void save(DecisionSummary summary);

    record DecisionSummary(
            String action,
            String toolName,
            String outcome,
            String summary,
            List<String> evidenceIds) {

        public DecisionSummary {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }
}
