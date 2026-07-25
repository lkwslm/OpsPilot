package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceHealth;
import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceInstance;
import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceRole;
import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic selector. A later execution failure never causes implicit failover. */
public final class SourceSelector {
    public Selection select(List<SourceInstance> configured, SelectionRequest request) {
        if (request.modelProvidedUrl() != null) throw new SourceSelectionException("MODEL_SOURCE_URL_DENIED");
        List<SourceInstance> eligible = configured.stream()
                .filter(SourceInstance::enabled)
                .filter(source -> source.health() == SourceHealth.READY)
                .filter(source -> source.targetSystemId().equals(request.targetSystemId()))
                .filter(source -> source.resourceIds().contains(request.resourceId()))
                .filter(source -> source.signals().contains(request.signal()))
                .filter(source -> source.queryTemplateIds().contains(request.queryTemplateId()))
                .filter(source -> request.requiredSourceId() == null
                        || source.sourceId().equals(request.requiredSourceId()))
                .sorted(Comparator.comparingInt(SourceInstance::priority).thenComparing(SourceInstance::sourceId))
                .toList();
        if (eligible.isEmpty()) throw new SourceSelectionException("SOURCE_NOT_CONFIGURED");
        if (request.explicitMultiSource()) {
            return new Selection(eligible.stream()
                    .filter(source -> source.role() != SourceRole.FALLBACK_DISABLED).toList(), true);
        }
        SourceInstance selected = eligible.stream()
                .filter(source -> source.role() == SourceRole.PRIMARY || eligible.size() == 1)
                .findFirst().orElseThrow(() -> new SourceSelectionException("PRIMARY_SOURCE_NOT_READY"));
        return new Selection(List.of(selected), false);
    }

    public record SelectionRequest(
            String targetSystemId, String runId, String resourceId, SignalType signal,
            String queryTemplateId, String requiredSourceId, boolean explicitMultiSource,
            String modelProvidedUrl) {
        public SelectionRequest {
            Objects.requireNonNull(targetSystemId, "targetSystemId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(signal, "signal");
            Objects.requireNonNull(queryTemplateId, "queryTemplateId");
        }
    }

    public record Selection(List<SourceInstance> sources, boolean explicitMultiSource) {
        public Selection { sources = List.copyOf(sources); }
    }

    public static final class SourceSelectionException extends RuntimeException {
        public SourceSelectionException(String code) { super(code); }
    }
}
