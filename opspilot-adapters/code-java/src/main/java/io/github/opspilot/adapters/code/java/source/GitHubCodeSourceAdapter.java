package io.github.opspilot.adapters.code.java.source;

import io.github.opspilot.core.port.code.CodeContracts.SourceKind;

import java.time.Clock;
import java.net.URI;
import java.util.Set;

public final class GitHubCodeSourceAdapter extends AbstractCodeHostingAdapter {
    public GitHubCodeSourceAdapter(
            String sourceId, String adapterVersion, Set<String> repositories, String connectionRef,
            PageFetcher fetcher, SecureCodeSnapshotMaterializer materializer, Clock clock) {
        super(sourceId, SourceKind.GITHUB, "github-code-source", adapterVersion,
                URI.create("https://api.github.com"), Set.of("api.github.com"),
                repositories, connectionRef, fetcher, materializer, clock);
    }

    public GitHubCodeSourceAdapter(
            String sourceId, String adapterVersion, URI endpointOrigin, Set<String> allowedHosts,
            Set<String> repositories, String connectionRef, PageFetcher fetcher,
            SecureCodeSnapshotMaterializer materializer, Clock clock) {
        super(sourceId, SourceKind.GITHUB, "github-code-source", adapterVersion,
                endpointOrigin, allowedHosts, repositories, connectionRef, fetcher, materializer, clock);
    }
}
