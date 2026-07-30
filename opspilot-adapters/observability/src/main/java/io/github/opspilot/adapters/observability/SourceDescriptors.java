package io.github.opspilot.adapters.observability;

import java.util.Map;
import java.util.Set;

import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceKind;

final class SourceDescriptors {
    private SourceDescriptors() {
    }

    static SourceDescriptor of(String sourceId, SourceKind kind, String adapterId,
                               String connectionRef, SignalType... capabilities) {
        return new SourceDescriptor(
                sourceId, kind, adapterId, "1.0.0", connectionRef, "phase0",
                Map.of("cluster", "sample-compose", "namespace", "opspilot"), Set.of(capabilities));
    }
}
