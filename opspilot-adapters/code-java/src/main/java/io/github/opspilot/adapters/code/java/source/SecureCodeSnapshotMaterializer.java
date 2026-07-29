package io.github.opspilot.adapters.code.java.source;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;
import io.github.opspilot.core.port.code.CodeContracts.CodeSourceExecutionContext;
import io.github.opspilot.core.port.code.CodeContracts.ReadOnlyWorkspaceHandle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Materializes validated entries into isolated, opaque, read-only workspaces. */
public final class SecureCodeSnapshotMaterializer implements AutoCloseable {
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(
            ".java", ".xml", ".properties", ".yaml", ".yml", ".json", ".md", ".txt");
    private final Limits limits;
    private final Clock clock;
    private final Path root;
    private final Map<String, Path> workspaces = new ConcurrentHashMap<>();

    public SecureCodeSnapshotMaterializer(Limits limits, Clock clock, Path root) {
        this.limits = limits;
        this.clock = clock;
        this.root = root.toAbsolutePath().normalize();
        try { Files.createDirectories(this.root); }
        catch (IOException exception) { throw new CodeSourceException("CODE_WORKSPACE_CREATE_FAILED", "workspace"); }
    }

    public CodeSnapshot materialize(
            CodeSourceExecutionContext context, String repositoryId, String commitSha,
            List<CodeArchiveEntry> entries) {
        if (entries.isEmpty()) throw new CodeSourceException("CODE_ARCHIVE_EMPTY", "archive");
        if (entries.size() > limits.maxFiles()) throw new CodeSourceException("CODE_ARCHIVE_FILE_LIMIT", "archive");
        long total = entries.stream().mapToLong(entry -> entry.content().length).sum();
        if (total > limits.maxArchiveBytes()) throw new CodeSourceException("CODE_ARCHIVE_SIZE_LIMIT", "archive");

        String workspaceId = UUID.randomUUID().toString();
        Path workspace = root.resolve(workspaceId).normalize();
        if (!workspace.startsWith(root)) throw new CodeSourceException("CODE_PATH_ESCAPE", "workspace");
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            Files.createDirectory(workspace);
            for (CodeArchiveEntry entry : entries.stream().sorted(Comparator.comparing(CodeArchiveEntry::path)).toList()) {
                validateEntry(entry);
                Path target = workspace.resolve(entry.path()).normalize();
                if (!target.startsWith(workspace)) throw new CodeSourceException("CODE_PATH_ESCAPE", "archive.path");
                Files.createDirectories(target.getParent());
                Files.write(target, entry.content());
                hashes.put(entry.path().replace('\\', '/'), digest(entry.content()));
            }
            makeReadOnly(workspace);
            String manifest = hashes.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((a, b) -> a + "\n" + b).orElseThrow();
            String manifestHash = digest(manifest.getBytes(StandardCharsets.UTF_8));
            ArtifactId manifestArtifact = new ArtifactId(UUID.nameUUIDFromBytes(manifest.getBytes(StandardCharsets.UTF_8)));
            String reference = "artifact://code-workspace/" + workspaceId;
            workspaces.put(reference, workspace);
            return new CodeSnapshot(workspaceId, context.sourceId(), context.sourceKind(), context.adapterId(),
                    context.adapterVersion(), repositoryId, commitSha, manifestHash, manifestArtifact,
                    manifestHash, new ReadOnlyWorkspaceHandle(reference, true), hashes, clock.instant());
        } catch (CodeSourceException exception) {
            deleteWorkspace(workspace);
            throw exception;
        } catch (IOException exception) {
            deleteWorkspace(workspace);
            throw new CodeSourceException("CODE_WORKSPACE_WRITE_FAILED", "workspace");
        }
    }

    public Path requirePath(ReadOnlyWorkspaceHandle handle) {
        Path path = workspaces.get(handle.reference());
        if (path == null || !path.startsWith(root) || !Files.isDirectory(path)) {
            throw new CodeSourceException("CODE_WORKSPACE_NOT_FOUND", "workspace");
        }
        return path;
    }

    public void destroy(ReadOnlyWorkspaceHandle handle) {
        Path path = workspaces.remove(handle.reference());
        if (path != null) deleteWorkspace(path);
    }

    private void validateEntry(CodeArchiveEntry entry) {
        String path = entry.path();
        if (path == null || path.isBlank() || path.contains("\\") || path.contains(":")
                || path.startsWith("/") || Path.of(path).isAbsolute()
                || Path.of(path).normalize().startsWith("..")) {
            throw new CodeSourceException("CODE_PATH_ESCAPE", "archive.path");
        }
        if (entry.kind() == CodeArchiveEntry.EntryKind.SYMBOLIC_LINK) {
            throw new CodeSourceException("CODE_SYMLINK_DENIED", "archive.path");
        }
        if (entry.kind() == CodeArchiveEntry.EntryKind.SUBMODULE) {
            throw new CodeSourceException("CODE_SUBMODULE_DENIED", "archive.path");
        }
        if (entry.kind() == CodeArchiveEntry.EntryKind.LFS_POINTER) {
            throw new CodeSourceException("CODE_LFS_DENIED", "archive.path");
        }
        if (entry.content().length > limits.maxFileBytes()) {
            throw new CodeSourceException("CODE_FILE_SIZE_LIMIT", "archive.path");
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (ALLOWED_SUFFIXES.stream().noneMatch(lower::endsWith)
                || lower.startsWith(".git/hooks/") || lower.contains("/.git/hooks/")) {
            throw new CodeSourceException("CODE_FILE_TYPE_DENIED", "archive.path");
        }
    }

    private static void makeReadOnly(Path workspace) throws IOException {
        boolean posix = Files.getFileStore(workspace).supportsFileAttributeView("posix");
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (posix) {
                    Files.setPosixFilePermissions(path, Files.isDirectory(path)
                            ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
                            : EnumSet.of(PosixFilePermission.OWNER_READ));
                } else {
                    Files.setAttribute(path, "dos:readonly", true);
                }
            }
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null || !workspace.toAbsolutePath().normalize().startsWith(root)) return;
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                try { Files.setAttribute(path, "dos:readonly", false); }
                catch (IOException | UnsupportedOperationException ignored) { }
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A failed cleanup remains absent from the registry and cannot be resolved by an analyzer.
        }
    }

    private static String digest(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override public void close() {
        new ArrayList<>(workspaces.keySet()).forEach(reference -> destroy(new ReadOnlyWorkspaceHandle(reference, true)));
    }

    public record Limits(int maxFiles, long maxArchiveBytes, long maxFileBytes) {
        public Limits {
            if (maxFiles < 1 || maxArchiveBytes < 1 || maxFileBytes < 1 || maxFileBytes > maxArchiveBytes) {
                throw new IllegalArgumentException("invalid code archive limits");
            }
        }
    }
}
