package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.application.observability.ObservationValidationPipeline.ValidatedObservation;
import io.github.opspilot.core.application.observability.ObservationValidationPipeline.ArtifactVerifier;
import io.github.opspilot.core.application.observability.SourceConfigurationService.SourceInstance;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.core.port.observability.ObservationContracts;
import io.github.opspilot.core.port.observability.ObservationContracts.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selects explicitly configured Sources and never retries another Source after a technical failure. */
public final class DefaultObservabilityQueryService implements ObservabilityQueryPort {
    private final SourceConfigurationService configurations;
    private final SourceSelector selector;
    private final SourceAdapterRegistry adapters;
    private final ResourceTopologyService topology;
    private final ObservationIngestionService ingestion;
    private final ArtifactVerifier artifactVerifier;

    public DefaultObservabilityQueryService(
            SourceConfigurationService configurations, SourceSelector selector,
            SourceAdapterRegistry adapters, ResourceTopologyService topology,
            ObservationIngestionService ingestion, ArtifactVerifier artifactVerifier) {
        this.configurations = configurations;
        this.selector = selector;
        this.adapters = adapters;
        this.topology = topology;
        this.ingestion = ingestion;
        this.artifactVerifier = java.util.Objects.requireNonNull(artifactVerifier, "artifactVerifier");
    }

    @Override
    public QueryCollection collect(QueryCommand command) {
        var activeTopology = topology.active(command.targetSystemId(), command.windowEnd());
        topology.requireOwned(activeTopology, command.targetSystemId(), command.resource());
        var selection = selector.select(configurations.snapshot(), new SourceSelector.SelectionRequest(
                command.targetSystemId(), command.runId().toString(), command.resource().resourceId(),
                command.signal(), command.queryTemplateId(), command.requiredSourceId(),
                command.explicitMultiSource(), null));
        List<ObservationBatch> batches = new ArrayList<>();
        for (SourceInstance source : selection.sources()) {
            var adapter = adapters.require(source.adapterId(), source.adapterVersion());
            ObservationQuery query = new ObservationQuery(
                    command.queryTemplateId(), ObservationContracts.sha256(canonicalParameters(command.parameters())),
                    command.windowStart(), command.windowEnd(), command.resource());
            ObservationBatch batch = adapter.query(query,
                    SourceExecutionContext.authorizedUntil(Instant.now().plus(source.timeout())));
            var context = new ObservationValidationPipeline.ValidationContext(
                    command.targetSystemId(), command.runId(), command.taskId(), command.allowedResourceIds(),
                    Set.of(source.sourceId()), source.adapterId(), source.adapterVersion(),
                    source.queryTemplateIds(), activeTopology.version(), Duration.ofHours(1), Duration.ofMinutes(5),
                    Duration.ofHours(24), 16_000, 10 * 1024 * 1024, false, artifactVerifier);
            ValidatedObservation validated = ingestion.ingest(batch, context);
            batches.add(validated.canonicalBatch());
        }
        return new QueryCollection(batches);
    }

    private static String canonicalParameters(java.util.Map<String, Object> parameters) {
        return parameters.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("&"));
    }
}
