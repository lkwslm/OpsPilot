package io.github.opspilot.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.adapters.code.java.JavaCodeAnalyzer;
import io.github.opspilot.adapters.code.java.source.CodeArchiveEntry;
import io.github.opspilot.adapters.code.java.source.SecureCodeSnapshotMaterializer;
import io.github.opspilot.core.port.code.CodeContracts.CodeSourceExecutionContext;
import io.github.opspilot.core.port.code.CodeContracts.SourceKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Materializes the image-bundled Sample System source and runs the bounded Java analyzer. */
final class Phase7CodeAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final Path sourceRoot;
    private final Path workspaceRoot;
    private final String commitSha;

    Phase7CodeAnalyzer(Path sourceRoot, Path workspaceRoot, String commitSha) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        if (!commitSha.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("CODE_COMMIT_SHA_INVALID");
        }
        this.commitSha = commitSha;
    }

    String analyze(String requestJson) throws Exception {
        var request = JSON.readTree(requestJson);
        String runId = request.path("runId").asText();
        List<CodeArchiveEntry> entries = new ArrayList<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
                entries.add(new CodeArchiveEntry(relative, Files.readAllBytes(file),
                        CodeArchiveEntry.EntryKind.FILE));
            }
        }
        if (entries.isEmpty()) throw new IllegalStateException("CODE_SNAPSHOT_EMPTY");
        Clock clock = Clock.systemUTC();
        try (var materializer = new SecureCodeSnapshotMaterializer(
                new SecureCodeSnapshotMaterializer.Limits(128, 8L * 1024 * 1024, 512L * 1024),
                clock, workspaceRoot)) {
            Instant deadline = Instant.now().plusSeconds(45);
            var snapshot = materializer.materialize(new CodeSourceExecutionContext(
                    "bundled-sample-system", SourceKind.GITHUB, "bundled-source-v1", "1.0.0",
                    "secret://image-bundled-read-only-source", deadline),
                    "sample-system", commitSha, entries);
            var findings = new JavaCodeAnalyzer(materializer, clock).analyze(snapshot, deadline);
            var output = findings.stream().map(finding -> Map.ofEntries(
                    Map.entry("findingId", finding.findingId()),
                    Map.entry("ruleId", finding.ruleId()),
                    Map.entry("summary", finding.summary()),
                    Map.entry("relativePath", finding.relativePath()),
                    Map.entry("fileSha256", finding.fileSha256()),
                    Map.entry("startLine", finding.startLine()),
                    Map.entry("endLine", finding.endLine()),
                    Map.entry("analyzerId", finding.analyzerId()),
                    Map.entry("analyzerVersion", finding.analyzerVersion()),
                    Map.entry("artifactIds", finding.artifactIds().stream()
                            .map(id -> id.value().toString()).toList()))).toList();
            return JSON.writeValueAsString(Map.of(
                    "schemaVersion", "1.0.0",
                    "runId", runId,
                    "repositoryId", snapshot.repositoryId(),
                    "commitSha", snapshot.commitSha(),
                    "manifestArtifactId", snapshot.manifestArtifactId().value().toString(),
                    "manifestSha256", snapshot.manifestSha256(),
                    "fileCount", snapshot.fileHashes().size(),
                    "findings", output));
        }
    }
}
