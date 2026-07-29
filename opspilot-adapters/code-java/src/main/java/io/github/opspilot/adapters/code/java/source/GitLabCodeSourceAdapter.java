package io.github.opspilot.adapters.code.java.source;

import io.github.opspilot.core.port.code.CodeContracts.SourceKind;

import java.time.Clock;
import java.net.URI;
import java.util.Set;

public final class GitLabCodeSourceAdapter extends AbstractCodeHostingAdapter {
    public GitLabCodeSourceAdapter(
            String sourceId, String adapterVersion, Set<String> repositories, String connectionRef,
            PageFetcher fetcher, SecureCodeSnapshotMaterializer materializer, Clock clock) {
        super(sourceId, SourceKind.GITLAB, "gitlab-code-source", adapterVersion,
                URI.create("https://gitlab.com/api/v4"), Set.of("gitlab.com"),
                repositories, connectionRef, fetcher, materializer, clock);
    }

    public GitLabCodeSourceAdapter(
            String sourceId, String adapterVersion, URI endpointOrigin, Set<String> allowedHosts,
            Set<String> repositories, String connectionRef, PageFetcher fetcher,
            SecureCodeSnapshotMaterializer materializer, Clock clock) {
        super(sourceId, SourceKind.GITLAB, "gitlab-code-source", adapterVersion,
                endpointOrigin, allowedHosts, repositories, connectionRef, fetcher, materializer, clock);
    }
}
