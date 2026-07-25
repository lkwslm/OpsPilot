package io.github.opspilot.adapters.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.port.observability.ObservabilitySourceAdapter;
import io.github.opspilot.core.port.observability.ObservationContracts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.opspilot.adapters.observability.SourceFailure.Code.ARTIFACT_HASH_MISMATCH;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_AUTH_FAILED;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_CANCELLED;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_IO_FAILURE;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_SCHEMA_INVALID;
import static io.github.opspilot.adapters.observability.SourceFailure.Code.SOURCE_TIMEOUT;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationBatch;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuality;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationQuery;
import static io.github.opspilot.core.port.observability.ObservationContracts.ObservationRecord;
import static io.github.opspilot.core.port.observability.ObservationContracts.RawArtifact;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceDescriptor;
import static io.github.opspilot.core.port.observability.ObservationContracts.SourceExecutionContext;

abstract class AbstractObservabilityAdapter implements ObservabilitySourceAdapter {
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final SourceDescriptor descriptor;
    private final RawSourceReader reader;
    private final String mediaType;
    private final Set<String> queryTemplates;

    AbstractObservabilityAdapter(SourceDescriptor descriptor, RawSourceReader reader, String mediaType) {
        this(descriptor, reader, mediaType, Set.of("phase0/replay"));
    }

    AbstractObservabilityAdapter(
            SourceDescriptor descriptor, RawSourceReader reader, String mediaType, Set<String> queryTemplates) {
        this.descriptor = descriptor;
        this.reader = reader;
        this.mediaType = mediaType;
        this.queryTemplates = Set.copyOf(queryTemplates);
    }

    @Override
    public final SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public final ObservationBatch query(ObservationQuery query, SourceExecutionContext context) {
        validateQuery(query);
        validateContext(context);
        byte[] content = read(query, context);
        validateContext(context);
        String hash = ObservationContracts.sha256(content);
        if (context.expectedArtifactSha256() != null && !context.expectedArtifactSha256().equals(hash)) {
            throw failure(ARTIFACT_HASH_MISMATCH, "Raw Artifact SHA-256 does not match");
        }
        List<ParsedObservation> parsed;
        try {
            parsed = parse(content);
        } catch (Exception exception) {
            throw failure(SOURCE_SCHEMA_INVALID, "Source response does not match the adapter schema", exception);
        }
        Instant collectedAt = Instant.now();
        RawArtifact artifact = new RawArtifact(UUID.randomUUID(), mediaType, hash, content);
        List<ObservationRecord> observations = parsed.stream().map(item -> new ObservationRecord(
                UUID.randomUUID(), item.signalType(), query.resource(), item.observedAt(),
                SensitiveDataRedactor.redact(item.summary()), artifact.artifactId(), item.attributes(),
                new ObservationQuality(
                        Math.max(0, Duration.between(item.observedAt(), collectedAt).toSeconds()),
                        true, false, null, List.of()))).toList();
        return new ObservationBatch(
                "1.0.0", UUID.randomUUID(), descriptor, query, collectedAt, null, observations, artifact);
    }

    protected abstract List<ParsedObservation> parse(byte[] content) throws Exception;

    private void validateQuery(ObservationQuery query) {
        if (query == null || query.resource() == null || !queryTemplates.contains(query.templateId())
                || query.parameterHash() == null
                || !query.parameterHash().matches("sha256:[a-f0-9]{64}")
                || query.windowStart() == null || query.windowEnd() == null
                || !query.windowEnd().isAfter(query.windowStart())
                || Duration.between(query.windowStart(), query.windowEnd()).compareTo(Duration.ofHours(1)) > 0) {
            throw failure(SOURCE_SCHEMA_INVALID, "Observation query is outside the controlled template contract");
        }
    }

    protected RawSourceReader readerFor(ObservationQuery query) {
        return reader;
    }

    private byte[] read(ObservationQuery query, SourceExecutionContext context) {
        try {
            return readerFor(query).read(Duration.between(Instant.now(), context.deadline()));
        } catch (HttpStatusException exception) {
            if (exception.statusCode == 401 || exception.statusCode == 403) {
                throw failure(SOURCE_AUTH_FAILED, "Source rejected credentials");
            }
            throw failure(SOURCE_IO_FAILURE, "Source returned HTTP " + exception.statusCode);
        } catch (HttpTimeoutException exception) {
            throw failure(SOURCE_TIMEOUT, "Source request exceeded its deadline", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(SOURCE_CANCELLED, "Source request was cancelled", exception);
        } catch (IOException exception) {
            throw failure(SOURCE_IO_FAILURE, "Source request failed", exception);
        }
    }

    private void validateContext(SourceExecutionContext context) {
        if (context.cancelled().getAsBoolean()) {
            throw failure(SOURCE_CANCELLED, "Source request was cancelled");
        }
        if (!context.authorized()) {
            throw failure(SOURCE_AUTH_FAILED, "Source execution context is not authorized");
        }
        if (!context.deadline().isAfter(Instant.now())) {
            throw failure(SOURCE_TIMEOUT, "Source request deadline has expired");
        }
    }

    private SourceFailure failure(SourceFailure.Code code, String message) {
        return new SourceFailure(code, descriptor.sourceId(), message);
    }

    private SourceFailure failure(SourceFailure.Code code, String message, Throwable cause) {
        return new SourceFailure(code, descriptor.sourceId(), message, cause);
    }

    protected record ParsedObservation(
            SignalType signalType, Instant observedAt, String summary, Map<String, Object> attributes) {
        protected ParsedObservation {
            attributes = Map.copyOf(attributes);
        }
    }

    interface RawSourceReader {
        byte[] read(Duration timeout) throws IOException, InterruptedException;
    }

    static RawSourceReader httpReader(URI endpoint) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return timeout -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HttpStatusException(response.statusCode());
            }
            return response.body();
        };
    }

    static RawSourceReader fileReader(Path path) {
        return timeout -> Files.readAllBytes(path);
    }

    private static final class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            this.statusCode = statusCode;
        }
    }
}
