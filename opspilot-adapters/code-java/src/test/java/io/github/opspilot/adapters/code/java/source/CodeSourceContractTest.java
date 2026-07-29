package io.github.opspilot.adapters.code.java.source;

import io.github.opspilot.adapters.code.java.JavaAnalyzerRegistry;
import io.github.opspilot.adapters.code.java.JavaCodeAnalyzer;
import io.github.opspilot.core.application.code.CodeRevisionResolver;
import io.github.opspilot.core.application.code.CodeRevisionResolver.DeploymentRevision;
import io.github.opspilot.core.application.code.CodeSourceAdapterRegistry;
import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.code.CodeContracts.CodeSourceExecutionContext;
import io.github.opspilot.core.port.code.CodeContracts.SourceKind;
import io.github.opspilot.core.port.code.CodeSourcePort.CodeSourceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodeSourceContractTest {
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SHA = "a".repeat(40);
    private static final String IMAGE = "sha256:" + "b".repeat(64);
    @TempDir Path temporary;

    @Test
    void githubAndGitlabSharePaginationSnapshotAndAnalyzerContract() throws Exception {
        var fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                getClass().getResourceAsStream("/code-source/shared-contract.json"));
        assertEquals(List.of("GITHUB", "GITLAB"), java.util.stream.StreamSupport.stream(
                fixture.get("platforms").spliterator(), false)
                .map(com.fasterxml.jackson.databind.JsonNode::textValue).toList());
        for (SourceKind kind : SourceKind.values()) {
            Path root = temporary.resolve(kind.name().toLowerCase());
            try (SecureCodeSnapshotMaterializer materializer = materializer(root)) {
                AtomicInteger pages = new AtomicInteger();
                AbstractCodeHostingAdapter.PageFetcher fetcher = request -> {
                    int page = pages.incrementAndGet();
                    assertEquals(kind, request.kind());
                    assertEquals("secret://code/read-only", request.connectionRef());
                    assertEquals(SHA, request.commitSha());
                    var fixtureEntry = fixture.at("/pages/" + (page - 1) + "/0");
                    return page == 1
                            ? new AbstractCodeHostingAdapter.Page(List.of(entry(
                            fixtureEntry.get("path").textValue(), fixtureEntry.get("content").textValue())), "page-2")
                            : new AbstractCodeHostingAdapter.Page(List.of(entry(
                            fixtureEntry.get("path").textValue(), fixtureEntry.get("content").textValue())), null);
                };
                String adapterId = kind == SourceKind.GITHUB ? "github-code-source" : "gitlab-code-source";
                String sourceId = kind == SourceKind.GITHUB ? "github-primary" : "gitlab-primary";
                var adapter = kind == SourceKind.GITHUB
                        ? new GitHubCodeSourceAdapter(sourceId, "1.0.0", Set.of("sample/repo"),
                        "secret://code/read-only", fetcher, materializer, CLOCK)
                        : new GitLabCodeSourceAdapter(sourceId, "1.0.0", Set.of("sample/repo"),
                        "secret://code/read-only", fetcher, materializer, CLOCK);
                var context = new CodeSourceExecutionContext(sourceId, kind, adapterId, "1.0.0",
                        "secret://code/read-only", NOW.plusSeconds(30));
                var snapshot = adapter.materialize(new CodeSourceRequest("sample/repo", SHA, context));

                assertEquals(2, pages.get());
                assertEquals("sample/repo", snapshot.repositoryId());
                assertEquals(SHA, snapshot.commitSha());
                assertEquals(2, snapshot.fileHashes().size());
                assertTrue(snapshot.manifestSha256().startsWith("sha256:"));
                assertTrue(Files.isDirectory(materializer.requirePath(snapshot.workspace())));

                JavaCodeAnalyzer analyzer = new JavaCodeAnalyzer(materializer, CLOCK);
                var findings = analyzer.analyze(snapshot, NOW.plusSeconds(10));
                assertEquals(1, findings.size());
                assertEquals(snapshot.repositoryId(), findings.getFirst().repositoryId());
                assertEquals(snapshot.commitSha(), findings.getFirst().commitSha());
                assertEquals(snapshot.manifestArtifactId(), findings.getFirst().rootArtifactId());
                var evidence = new RuntimeEvidenceNormalizer().normalizeCode(snapshot, findings,
                        new NormalizationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
                assertEquals("CODE_FINDING", evidence.evidence().getFirst().provenanceRefs().getFirst().kind());

                Path workspace = materializer.requirePath(snapshot.workspace());
                materializer.destroy(snapshot.workspace());
                assertFalse(Files.exists(workspace));
                assertThrows(CodeSourceException.class, () -> materializer.requirePath(snapshot.workspace()));
            }
        }
    }

    @Test
    void repositoriesAndCommitsRemainIndependentAndProvenanceMismatchCannotNormalize() {
        try (SecureCodeSnapshotMaterializer materializer = materializer(temporary.resolve("multi"))) {
            var context = new CodeSourceExecutionContext("github-primary", SourceKind.GITHUB,
                    "github-code-source", "1.0.0", "secret://code/read-only", NOW.plusSeconds(30));
            var first = materializer.materialize(context, "repo/a", SHA,
                    List.of(entry("src/A.java", "class A { int timeout = 1; }")));
            var second = materializer.materialize(context, "repo/b", "c".repeat(40),
                    List.of(entry("src/B.java", "class B { int retry = 1; }")));
            assertNotEquals(first.workspace(), second.workspace());
            assertNotEquals(first.manifestArtifactId(), second.manifestArtifactId());
            var finding = new JavaCodeAnalyzer(materializer, CLOCK)
                    .analyze(first, NOW.plusSeconds(10)).getFirst();
            var forged = new io.github.opspilot.core.port.code.CodeContracts.CodeFinding(
                    finding.findingId(), finding.ruleId(), finding.summary(), finding.relativePath(),
                    finding.fileSha256(), finding.startLine(), finding.endLine(), second.repositoryId(),
                    second.commitSha(), finding.rootArtifactId(), finding.analyzerId(), finding.analyzerVersion(),
                    finding.artifactIds());
            assertThrows(IllegalArgumentException.class, () -> new RuntimeEvidenceNormalizer().normalizeCode(
                    first, List.of(forged), new NormalizationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        }
    }

    @Test
    void archiveEscapeSymlinkSubmoduleLfsTypeAndLimitsFailBeforeWorkspacePublication() throws Exception {
        record Invalid(CodeArchiveEntry entry, String code) { }
        List<Invalid> invalid = List.of(
                new Invalid(entry("../escape.java", "x"), "CODE_PATH_ESCAPE"),
                new Invalid(entry("C:/escape.java", "x"), "CODE_PATH_ESCAPE"),
                new Invalid(new CodeArchiveEntry("src/link.java", new byte[0], CodeArchiveEntry.EntryKind.SYMBOLIC_LINK), "CODE_SYMLINK_DENIED"),
                new Invalid(new CodeArchiveEntry("module.java", new byte[0], CodeArchiveEntry.EntryKind.SUBMODULE), "CODE_SUBMODULE_DENIED"),
                new Invalid(new CodeArchiveEntry("large.java", new byte[0], CodeArchiveEntry.EntryKind.LFS_POINTER), "CODE_LFS_DENIED"),
                new Invalid(entry(".git/hooks/pre-commit.java", "class Hook {}"), "CODE_FILE_TYPE_DENIED"),
                new Invalid(entry("script.sh", "echo unsafe"), "CODE_FILE_TYPE_DENIED"));
        int index = 0;
        for (Invalid fixture : invalid) {
            Path root = temporary.resolve("invalid-" + index++);
            try (SecureCodeSnapshotMaterializer materializer = materializer(root)) {
                CodeSourceException failure = assertThrows(CodeSourceException.class,
                        () -> materializer.materialize(context(), "repo", SHA, List.of(fixture.entry())));
                assertEquals(fixture.code(), failure.code());
                try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
            }
        }
        try (SecureCodeSnapshotMaterializer limited = new SecureCodeSnapshotMaterializer(
                new SecureCodeSnapshotMaterializer.Limits(1, 8, 8), CLOCK, temporary.resolve("limits"))) {
            assertEquals("CODE_ARCHIVE_FILE_LIMIT", assertThrows(CodeSourceException.class,
                    () -> limited.materialize(context(), "repo", SHA,
                            List.of(entry("A.java", "a"), entry("B.java", "b")))).code());
            assertEquals("CODE_ARCHIVE_SIZE_LIMIT", assertThrows(CodeSourceException.class,
                    () -> limited.materialize(context(), "repo", SHA,
                            List.of(entry("A.java", "more than eight")))).code());
        }
    }

    @Test
    void unauthorizedRepositoryContextAndExpiredDeadlineDoNotFetch() {
        try (SecureCodeSnapshotMaterializer materializer = materializer(temporary.resolve("auth"))) {
            AtomicInteger fetches = new AtomicInteger();
            var adapter = new GitHubCodeSourceAdapter("github-primary", "1.0.0", Set.of("allowed/repo"),
                    "secret://code/read-only", request -> {
                        fetches.incrementAndGet();
                        return new AbstractCodeHostingAdapter.Page(List.of(entry("A.java", "class A {}")), null);
                    }, materializer, CLOCK);
            assertEquals("CODE_SOURCE_NOT_CONFIGURED", assertThrows(CodeSourceException.class,
                    () -> adapter.materialize(new CodeSourceRequest("https://evil/repo", SHA, context()))).code());
            var wrongSecret = new CodeSourceExecutionContext("github-primary", SourceKind.GITHUB,
                    "github-code-source", "1.0.0", "secret://other", NOW.plusSeconds(10));
            assertEquals("CODE_SOURCE_NOT_CONFIGURED", assertThrows(CodeSourceException.class,
                    () -> adapter.materialize(new CodeSourceRequest("allowed/repo", SHA, wrongSecret))).code());
            var expired = new CodeSourceExecutionContext("github-primary", SourceKind.GITHUB,
                    "github-code-source", "1.0.0", "secret://code/read-only", NOW);
            assertEquals("CODE_SOURCE_TIMEOUT", assertThrows(CodeSourceException.class,
                    () -> adapter.materialize(new CodeSourceRequest("allowed/repo", SHA, expired))).code());
            var cancelled = new CodeSourceExecutionContext("github-primary", SourceKind.GITHUB,
                    "github-code-source", "1.0.0", "secret://code/read-only", NOW.plusSeconds(10), () -> true);
            assertEquals("CODE_SOURCE_CANCELLED", assertThrows(CodeSourceException.class,
                    () -> adapter.materialize(new CodeSourceRequest("allowed/repo", SHA, cancelled))).code());
            assertEquals("CODE_SOURCE_HOST_DENIED", assertThrows(CodeSourceException.class,
                    () -> new GitHubCodeSourceAdapter("github-primary", "1.0.0",
                            URI.create("https://evil.example/api"), Set.of("api.github.com"), Set.of("allowed/repo"),
                            "secret://code/read-only", request -> { throw new AssertionError(); }, materializer, CLOCK)).code());
            assertEquals(0, fetches.get());
        }
    }

    @Test
    void revisionAndRegistriesRejectAmbiguityBranchShortShaAndVersionMismatch() {
        DeploymentRevision good = new DeploymentRevision("service:order", IMAGE, "sample/repo", SHA, "RELEASE_MANIFEST");
        var resolver = new CodeRevisionResolver(Map.of("service:order", List.of(good)), Set.of("sample/repo"));
        assertEquals(SHA, resolver.resolve("service:order").commitSha());
        assertResolution("CODE_REVISION_UNRESOLVED", new CodeRevisionResolver(Map.of(), Set.of()), "missing");
        assertResolution("CODE_REVISION_UNRESOLVED", new CodeRevisionResolver(
                Map.of("service:order", List.of(good, good)), Set.of("sample/repo")), "service:order");
        assertResolution("CODE_REVISION_UNRESOLVED", new CodeRevisionResolver(
                Map.of("service:order", List.of(new DeploymentRevision(
                        "service:order", IMAGE, "sample/repo", "main", "IMAGE_LABEL"))), Set.of("sample/repo")), "service:order");
        assertResolution("CODE_SOURCE_NOT_CONFIGURED", new CodeRevisionResolver(
                Map.of("service:order", List.of(good)), Set.of()), "service:order");

        try (SecureCodeSnapshotMaterializer materializer = materializer(temporary.resolve("registries"))) {
            var adapter = new GitHubCodeSourceAdapter("github-primary", "1.0.0", Set.of("sample/repo"),
                    "secret://code/read-only", request -> new AbstractCodeHostingAdapter.Page(
                    List.of(entry("A.java", "class A {}")), null), materializer, CLOCK);
            CodeSourceAdapterRegistry sources = new CodeSourceAdapterRegistry();
            sources.register(SourceKind.GITHUB, "github-code-source", "1.0.0", adapter);
            assertEquals("CODE_SOURCE_ADAPTER_VERSION_INCOMPATIBLE", assertThrows(
                    CodeSourceAdapterRegistry.RegistryException.class,
                    () -> sources.freeze(Map.of(SourceKind.GITHUB, "2.0.0"))).code());

            JavaAnalyzerRegistry analyzers = new JavaAnalyzerRegistry();
            analyzers.register(JavaCodeAnalyzer.ANALYZER_ID, JavaCodeAnalyzer.ANALYZER_VERSION,
                    new JavaCodeAnalyzer(materializer, CLOCK));
            analyzers.freeze(Map.of(JavaCodeAnalyzer.ANALYZER_ID, JavaCodeAnalyzer.ANALYZER_VERSION));
            assertEquals("ANALYZER_REGISTRY_FROZEN", assertThrows(JavaAnalyzerRegistry.RegistryException.class,
                    () -> analyzers.register("other", "1.0.0", (snapshot, deadline) -> List.of())).code());
        }
    }

    private static void assertResolution(String code, CodeRevisionResolver resolver, String resource) {
        assertEquals(code, assertThrows(CodeRevisionResolver.ResolutionException.class,
                () -> resolver.resolve(resource)).code());
    }

    private SecureCodeSnapshotMaterializer materializer(Path root) {
        return new SecureCodeSnapshotMaterializer(
                new SecureCodeSnapshotMaterializer.Limits(100, 1_000_000, 100_000), CLOCK, root);
    }

    private static CodeSourceExecutionContext context() {
        return new CodeSourceExecutionContext("github-primary", SourceKind.GITHUB,
                "github-code-source", "1.0.0", "secret://code/read-only", NOW.plusSeconds(30));
    }

    private static CodeArchiveEntry entry(String path, String content) {
        return new CodeArchiveEntry(path, content.getBytes(StandardCharsets.UTF_8), CodeArchiveEntry.EntryKind.FILE);
    }
}
