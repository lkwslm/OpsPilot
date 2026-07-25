package io.github.opspilot.server;

import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService;
import io.github.opspilot.adapters.persistence.postgres.PostgresCheckpointUnitOfWork;
import io.github.opspilot.adapters.persistence.postgres.PostgresIncidentAgentStateRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresProjectionReceiptRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresReadinessCheck;
import io.github.opspilot.adapters.persistence.postgres.SseEventRepository;
import io.github.opspilot.adapters.observability.*;
import io.github.opspilot.core.application.observability.SourceAdapterRegistry;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.extension.ExtensionContracts.ExtensionDescriptor;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import io.github.opspilot.tools.defaults.*;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolAuditSink;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolAuthorizationPort;

/** The only production entry point allowed to construct concrete OpsPilot components. */
public final class OpsPilotCompositionRoot {
    private OpsPilotCompositionRoot() {
    }

    public static Components composeCore() {
        return composeCore(List.of());
    }

    /** Extensions are passed explicitly by deployment configuration; there is no runtime discovery. */
    public static Components composeCore(List<ExtensionDescriptor> extensions) {
        return new Components(new RuntimeEvidenceNormalizer(), extensions);
    }

    /** Production persistence is PostgreSQL-only and never substitutes an in-memory adapter. */
    public static PersistenceComponents composePersistence(
            DataSource dataSource, Path artifactRoot, long maxArtifactBytes) {
        return new PersistenceComponents(
                new PostgresIncidentAgentStateRepository(dataSource),
                new PostgresCheckpointUnitOfWork(dataSource),
                new PostgresProjectionReceiptRepository(dataSource, "opspilot-default-projector"),
                new DurableTaskRepository(dataSource),
                new SseEventRepository(dataSource),
                new LocalVolumeArtifactAccessService(dataSource, artifactRoot, maxArtifactBytes),
                new PostgresReadinessCheck(dataSource, "8", "0.8.4"));
    }

    /** Adapter implementations are explicitly enumerated here; no classpath discovery is used. */
    public static SourceAdapterRegistry composeObservabilityAdapters(ObservabilityAdapterConfig config) {
        Map<String, ControlledSecretResolver.Binding> bindings = new LinkedHashMap<>();
        bindings.put("observability-source://phase0/prometheus",
                ControlledSecretResolver.Binding.withoutSecret("sample-commerce", config.prometheusBaseUri().toString()));
        if (config.jaegerV1BaseUri() != null) {
            bindings.put("observability-source://phase0/jaeger-v1",
                    ControlledSecretResolver.Binding.withoutSecret(
                            "sample-commerce", config.jaegerV1BaseUri().toString()));
        }
        if (config.jaegerV2BaseUri() != null) {
            bindings.put("observability-source://phase0/jaeger-v2",
                    ControlledSecretResolver.Binding.withoutSecret(
                            "sample-commerce", config.jaegerV2BaseUri().toString()));
        }
        bindings.put("observability-source://sample/actuator",
                ControlledSecretResolver.Binding.withoutSecret("sample-commerce", config.actuatorHealthUri().toString()));
        bindings.put("observability-source://sample/http-health",
                ControlledSecretResolver.Binding.withoutSecret("sample-commerce", config.httpHealthUri().toString()));
        ControlledSecretResolver resolver = new ControlledSecretResolver(bindings);
        SourceAdapterRegistry registry = new SourceAdapterRegistry();
        registry.register(SecretResolvingSourceAdapter.prometheus(resolver, "sample-commerce"));
        if (config.jaegerV1BaseUri() != null) {
            registry.register(SecretResolvingSourceAdapter.jaegerV1(resolver, "sample-commerce"));
        }
        if (config.jaegerV2BaseUri() != null) {
            registry.register(SecretResolvingSourceAdapter.jaegerV2(resolver, "sample-commerce"));
        }
        registry.register(new JsonlLogAdapter(config.jsonlPath()));
        registry.register(SecretResolvingSourceAdapter.actuator(resolver, "sample-commerce"));
        registry.register(new StaticComposeTopologyAdapter(config.composePath()));
        registry.register(SecretResolvingSourceAdapter.httpHealth(resolver, "sample-commerce"));
        registry.register(new ControlledConfigAdapter(config.configPath(), config.configAllowlist()));
        registry.probeAndFreeze();
        return registry;
    }

    public static List<ObservabilityQueryTool> composeObservabilityTools(
            ObservabilityQueryPort port, EvidenceNormalizer normalizer,
            ToolAuthorizationPort authorization, ToolAuditSink audit) {
        return List.of(
                new LogQueryTool(port, normalizer, authorization, audit),
                new MetricQueryTool(port, normalizer, authorization, audit),
                new TraceQueryTool(port, normalizer, authorization, audit),
                new HealthQueryTool(port, normalizer, authorization, audit),
                new TopologyQueryTool(port, normalizer, authorization, audit),
                new ConfigReadTool(port, normalizer, authorization, audit));
    }

    public record Components(EvidenceNormalizer evidenceNormalizer, List<ExtensionDescriptor> extensions) {
        public Components { extensions = List.copyOf(extensions); }
    }

    public record PersistenceComponents(
            PostgresIncidentAgentStateRepository agentStateRepository,
            PostgresCheckpointUnitOfWork checkpointUnitOfWork,
            PostgresProjectionReceiptRepository projectionReceiptRepository,
            DurableTaskRepository durableTaskRepository,
            SseEventRepository sseEventRepository,
            LocalVolumeArtifactAccessService artifactAccessService,
            PostgresReadinessCheck readinessCheck) { }

    public record ObservabilityAdapterConfig(
            URI prometheusBaseUri, URI jaegerV1BaseUri, URI jaegerV2BaseUri,
            Path jsonlPath, URI actuatorHealthUri,
            Path composePath, URI httpHealthUri, Path configPath, Set<String> configAllowlist) {
        public ObservabilityAdapterConfig {
            Objects.requireNonNull(prometheusBaseUri, "prometheusBaseUri");
            if (jaegerV1BaseUri == null && jaegerV2BaseUri == null) {
                throw new IllegalArgumentException("At least one Jaeger version must be configured");
            }
            configAllowlist = Set.copyOf(configAllowlist);
        }
    }
}
