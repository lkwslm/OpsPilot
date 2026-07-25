package io.github.opspilot.adapters.observability;

import io.github.opspilot.core.port.observability.ObservabilitySourceAdapter;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;
import io.github.opspilot.core.port.observability.SecretResolver;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

/** Resolves endpoint material only for the duration of a single adapter invocation. */
public final class SecretResolvingSourceAdapter implements ObservabilitySourceAdapter {
    private final SourceDescriptor descriptor;
    private final String targetSystemId;
    private final SecretResolver resolver;
    private final Function<URI, ObservabilitySourceAdapter> factory;

    private SecretResolvingSourceAdapter(
            SourceDescriptor descriptor, String targetSystemId, SecretResolver resolver,
            Function<URI, ObservabilitySourceAdapter> factory) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.targetSystemId = Objects.requireNonNull(targetSystemId, "targetSystemId");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public static SecretResolvingSourceAdapter prometheus(SecretResolver resolver, String targetSystemId) {
        return new SecretResolvingSourceAdapter(
                SourceDescriptors.of("phase0-prometheus", io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.PROMETHEUS,
                        "prometheus-metric", "observability-source://phase0/prometheus",
                        io.github.opspilot.core.port.observability.ObservationContracts.SignalType.METRIC),
                targetSystemId, resolver, PrometheusMetricAdapter::new);
    }

    public static SecretResolvingSourceAdapter jaeger(SecretResolver resolver, String targetSystemId) {
        return jaegerV1(resolver, targetSystemId);
    }

    public static SecretResolvingSourceAdapter jaegerV1(SecretResolver resolver, String targetSystemId) {
        return new SecretResolvingSourceAdapter(
                JaegerTraceAdapter.v1Descriptor(), targetSystemId, resolver, JaegerV1TraceAdapter::new);
    }

    public static SecretResolvingSourceAdapter jaegerV2(SecretResolver resolver, String targetSystemId) {
        return new SecretResolvingSourceAdapter(
                JaegerTraceAdapter.v2Descriptor(), targetSystemId, resolver, JaegerV2TraceAdapter::new);
    }

    public static SecretResolvingSourceAdapter actuator(SecretResolver resolver, String targetSystemId) {
        return new SecretResolvingSourceAdapter(
                SourceDescriptors.of("sample-actuator", io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.HTTP,
                        "spring-actuator-health", "observability-source://sample/actuator",
                        io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH),
                targetSystemId, resolver, SpringActuatorHealthAdapter::new);
    }

    public static SecretResolvingSourceAdapter httpHealth(SecretResolver resolver, String targetSystemId) {
        return new SecretResolvingSourceAdapter(
                SourceDescriptors.of("sample-http-health", io.github.opspilot.core.port.observability.ObservationContracts.SourceKind.HTTP,
                        "http-health", "observability-source://sample/http-health",
                        io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH),
                targetSystemId, resolver, HttpHealthAdapter::new);
    }

    @Override public SourceDescriptor descriptor() { return descriptor; }

    @Override
    public ObservationBatch query(ObservationQuery query, SourceExecutionContext context) {
        if (query == null || query.resource() == null
                || !targetSystemId.equals(query.resource().systemId())) {
            throw new ControlledSecretResolver.SecretResolutionException("CONNECTION_REF_TARGET_MISMATCH");
        }
        try (var resolved = resolver.resolve(descriptor.connectionRef(),
                new SecretResolver.ResolutionContext("adapter:" + descriptor.adapterId(), targetSystemId,
                        context != null && context.authorized()))) {
            URI endpoint = controlledEndpoint(resolved.endpoint());
            ObservationBatch batch = factory.apply(endpoint).query(query, context);
            if (!descriptor.equals(batch.source())) {
                throw new IllegalStateException("ADAPTER_DESCRIPTOR_MISMATCH");
            }
            return batch;
        }
    }

    private static URI controlledEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new ControlledSecretResolver.SecretResolutionException("SOURCE_ENDPOINT_INVALID");
        }
        return uri;
    }
}
