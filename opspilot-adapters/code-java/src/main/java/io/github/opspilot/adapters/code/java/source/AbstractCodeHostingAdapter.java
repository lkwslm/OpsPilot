package io.github.opspilot.adapters.code.java.source;

import io.github.opspilot.core.port.code.CodeContracts.SourceKind;
import io.github.opspilot.core.port.code.CodeSourcePort;

import java.time.Clock;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

abstract class AbstractCodeHostingAdapter implements CodeSourcePort {
    private static final int MAX_PAGES = 100;
    private final String sourceId;
    private final SourceKind kind;
    private final String adapterId;
    private final String adapterVersion;
    private final Set<String> repositories;
    private final String connectionRef;
    private final URI endpointOrigin;
    private final PageFetcher fetcher;
    private final SecureCodeSnapshotMaterializer materializer;
    private final Clock clock;

    AbstractCodeHostingAdapter(
            String sourceId, SourceKind kind, String adapterId, String adapterVersion,
            URI endpointOrigin, Set<String> allowedHosts, Set<String> repositories,
            String connectionRef, PageFetcher fetcher,
            SecureCodeSnapshotMaterializer materializer, Clock clock) {
        this.sourceId = require(sourceId, "sourceId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.adapterId = require(adapterId, "adapterId");
        this.adapterVersion = require(adapterVersion, "adapterVersion");
        this.endpointOrigin = validateEndpoint(endpointOrigin, allowedHosts);
        this.repositories = Set.copyOf(repositories);
        if (!connectionRef.startsWith("secret://")) throw new IllegalArgumentException("connectionRef must be a Secret reference");
        this.connectionRef = connectionRef;
        this.fetcher = fetcher;
        this.materializer = materializer;
        this.clock = clock;
    }

    @Override
    public final io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot materialize(CodeSourceRequest request) {
        var context = request.executionContext();
        if (!sourceId.equals(context.sourceId()) || kind != context.sourceKind()
                || !adapterId.equals(context.adapterId()) || !adapterVersion.equals(context.adapterVersion())
                || !connectionRef.equals(context.connectionRef())) {
            throw new CodeSourceException("CODE_SOURCE_NOT_CONFIGURED", "executionContext");
        }
        if (!repositories.contains(request.repositoryId())) {
            throw new CodeSourceException("CODE_SOURCE_NOT_CONFIGURED", "repositoryId");
        }
        if (context.cancellation().cancelled()) {
            throw new CodeSourceException("CODE_SOURCE_CANCELLED", "cancellation");
        }
        if (!context.deadline().isAfter(clock.instant())) {
            throw new CodeSourceException("CODE_SOURCE_TIMEOUT", "deadline");
        }
        List<CodeArchiveEntry> entries = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
            Page page = fetcher.fetch(new FetchRequest(kind, endpointOrigin, request.repositoryId(), request.commitSha(),
                    connectionRef, cursor, context.deadline()));
            if (context.cancellation().cancelled()) {
                throw new CodeSourceException("CODE_SOURCE_CANCELLED", "cancellation");
            }
            if (!context.deadline().isAfter(clock.instant())) {
                throw new CodeSourceException("CODE_SOURCE_TIMEOUT", "deadline");
            }
            entries.addAll(page.entries());
            cursor = page.nextCursor();
            if (cursor == null) return materializer.materialize(context, request.repositoryId(), request.commitSha(), entries);
            if (context.cancellation().cancelled()) {
                throw new CodeSourceException("CODE_SOURCE_CANCELLED", "cancellation");
            }
            if (!context.deadline().isAfter(clock.instant())) {
                throw new CodeSourceException("CODE_SOURCE_TIMEOUT", "deadline");
            }
        }
        throw new CodeSourceException("CODE_SOURCE_PAGE_LIMIT", "archive");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static URI validateEndpoint(URI endpoint, Set<String> allowedHosts) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !allowedHosts.contains(endpoint.getHost().toLowerCase(java.util.Locale.ROOT))) {
            throw new CodeSourceException("CODE_SOURCE_HOST_DENIED", "endpoint");
        }
        return endpoint;
    }

    @FunctionalInterface
    public interface PageFetcher { Page fetch(FetchRequest request); }
    public record FetchRequest(SourceKind kind, URI endpointOrigin, String repositoryId, String commitSha,
                               String connectionRef, String cursor, java.time.Instant deadline) { }
    public record Page(List<CodeArchiveEntry> entries, String nextCursor) {
        public Page { entries = List.copyOf(entries); }
    }
}
