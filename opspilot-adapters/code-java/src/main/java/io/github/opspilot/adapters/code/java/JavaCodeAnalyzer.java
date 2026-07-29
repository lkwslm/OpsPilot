package io.github.opspilot.adapters.code.java;

import io.github.opspilot.adapters.code.java.source.CodeSourceException;
import io.github.opspilot.adapters.code.java.source.SecureCodeSnapshotMaterializer;
import io.github.opspilot.core.port.code.CodeAnalysisPort;
import io.github.opspilot.core.port.code.CodeContracts.CodeFinding;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Bounded Java analyzer that consumes only a validated managed CodeSnapshot. */
public final class JavaCodeAnalyzer implements CodeAnalysisPort {
    public static final String ANALYZER_ID = "java-semantic-v1";
    public static final String ANALYZER_VERSION = "1.0.0";
    private final SecureCodeSnapshotMaterializer workspaces;
    private final Clock clock;

    public JavaCodeAnalyzer(SecureCodeSnapshotMaterializer workspaces, Clock clock) {
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Override
    public List<CodeFinding> analyze(CodeSnapshot snapshot, Instant deadline) {
        if (deadline == null || !deadline.isAfter(clock.instant())) {
            throw new CodeSourceException("CODE_ANALYSIS_TIMEOUT", "deadline");
        }
        Path root = workspaces.requirePath(snapshot.workspace());
        List<CodeFinding> findings = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList()) {
                if (!deadline.isAfter(clock.instant())) throw new CodeSourceException("CODE_ANALYSIS_TIMEOUT", "deadline");
                String relative = root.relativize(file).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(file);
                String actualHash = digest(bytes);
                if (!actualHash.equals(snapshot.fileHashes().get(relative))) {
                    throw new CodeSourceException("CODE_SNAPSHOT_HASH_MISMATCH", "fileHashes." + relative);
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String lower = lines.get(index).toLowerCase(Locale.ROOT);
                    if (lower.contains("timeout") || lower.contains("retry")) {
                        findings.add(new CodeFinding(UUID.randomUUID().toString(), "RETRY_TIMEOUT",
                                "Java source declares timeout or retry behavior", relative, actualHash,
                                index + 1, index + 1, snapshot.repositoryId(), snapshot.commitSha(),
                                snapshot.manifestArtifactId(), ANALYZER_ID, ANALYZER_VERSION,
                                List.of(snapshot.manifestArtifactId())));
                    }
                }
            }
            return List.copyOf(findings);
        } catch (IOException exception) {
            throw new CodeSourceException("CODE_ANALYSIS_READ_FAILED", "workspace");
        }
    }

    private static String digest(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
