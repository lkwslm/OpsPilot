package io.github.opspilot.core.port.observability;

import io.github.opspilot.core.port.observability.ObservationContracts.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Vendor-neutral orchestration boundary consumed by observability Tools. */
public interface ObservabilityQueryPort {
    QueryCollection collect(QueryCommand command);

    record QueryCommand(
            String targetSystemId, UUID runId, UUID taskId, ResourceRef resource, SignalType signal,
            String queryTemplateId, Map<String, Object> parameters, Instant windowStart, Instant windowEnd,
            String requiredSourceId, boolean explicitMultiSource, Set<String> allowedResourceIds) {
        public QueryCommand {
            parameters = Map.copyOf(parameters);
            allowedResourceIds = Set.copyOf(allowedResourceIds);
        }
    }

    record QueryCollection(List<ObservationBatch> batches) {
        public QueryCollection { batches = List.copyOf(batches); }
    }
}
