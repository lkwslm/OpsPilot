package io.github.opspilot.core.port.observability;

import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

public interface ObservabilitySourceAdapter {
    SourceDescriptor descriptor();

    ObservationBatch query(ObservationQuery query, SourceExecutionContext context);

    /** Startup probe used before a registry publishes its immutable capability snapshot. */
    default ProbeResult probe() {
        SourceDescriptor descriptor = descriptor();
        boolean valid = descriptor != null
                && descriptor.adapterId() != null && !descriptor.adapterId().isBlank()
                && descriptor.adapterVersion() != null
                && descriptor.adapterVersion().matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                && descriptor.capabilities() != null && !descriptor.capabilities().isEmpty();
        return valid ? ProbeResult.success() : ProbeResult.failed("ADAPTER_CONTRACT_INVALID");
    }

    record ProbeResult(boolean ready, String reason) {
        public static ProbeResult success() { return new ProbeResult(true, "READY"); }
        public static ProbeResult failed(String reason) { return new ProbeResult(false, reason); }
    }
}
