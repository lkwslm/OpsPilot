package io.github.opspilot.core.port.observability;

import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

public interface ObservabilitySourceAdapter {
    SourceDescriptor descriptor();

    ObservationBatch query(ObservationQuery query, SourceExecutionContext context);
}
