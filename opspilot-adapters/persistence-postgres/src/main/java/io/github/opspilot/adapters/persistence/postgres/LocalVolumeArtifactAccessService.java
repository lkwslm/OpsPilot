package io.github.opspilot.adapters.persistence.postgres;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.artifact.ArtifactPort;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Controlled local-volume Artifact adapter with immutable metadata and reconciliation. */
public final class LocalVolumeArtifactAccessService implements ArtifactPort {
    private static final String PROVIDER = "local-volume";
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    private final DataSource dataSource;
    private final Path root;
    private final long maxBytes;
    private final Clock clock;
    private final AccessAuthorizer authorizer;

    public LocalVolumeArtifactAccessService(DataSource dataSource, Path root, long maxBytes) {
        this(dataSource, root, maxBytes, Clock.systemUTC(), AccessAuthorizer.sameRun());
    }

    public LocalVolumeArtifactAccessService(
            DataSource dataSource,
            Path root,
            long maxBytes,
            Clock clock,
            AccessAuthorizer authorizer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.maxBytes = maxBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.root = initializeRoot(root);
    }

    @Override
    public ArtifactId store(RunId runId, String mediaType, byte[] content) {
        Objects.requireNonNull(content, "content");
        UUID artifactId = UUID.randomUUID();
        store(new WriteRequest(
                artifactId, runId.value(), null, mediaType, AccessLevel.RUN_PRIVATE,
                RetentionClass.INCIDENT_RUN, new ByteArrayInputStream(content)));
        return new ArtifactId(artifactId);
    }

    public Metadata store(WriteRequest request) {
        Objects.requireNonNull(request, "request");
        String objectKey = request.artifactId().toString().substring(0, 2)
                + "/" + request.artifactId();
        Path target = resolveObjectKey(objectKey);
        try {
            Files.createDirectories(target.getParent());
            rejectSymlinkPath(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactConflictException();
            }
            Path temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
            try {
                DigestAndSize digest = writeTemporary(request.content(), temporary);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IllegalStateException("ARTIFACT_ATOMIC_MOVE_UNSUPPORTED", exception);
                }
                Metadata metadata = new Metadata(
                        request.artifactId(), request.runId(), request.taskId(), PROVIDER, objectKey,
                        "artifact://" + PROVIDER + "/" + objectKey, digest.sha256(), digest.sizeBytes(),
                        required(request.mediaType(), "mediaType"), request.accessLevel(),
                        LifecycleStatus.AVAILABLE, request.retentionClass(),
                        expiresAt(clock.instant(), request.retentionClass()));
                try {
                    insertMetadata(metadata);
                } catch (RuntimeException failure) {
                    Files.deleteIfExists(target);
                    throw failure;
                }
                return metadata;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ARTIFACT_WRITE_FAILED", exception);
        }
    }

    public byte[] read(UUID artifactId, AccessContext access) {
        Metadata metadata = findMetadata(artifactId);
        if (metadata.lifecycleStatus() != LifecycleStatus.AVAILABLE
                || !authorizer.allowed(access, metadata)) {
            throw new ArtifactAccessDeniedException();
        }
        if (metadata.sizeBytes() > maxBytes) {
            throw new ArtifactSizeLimitException();
        }
        Path path = resolveObjectKey(metadata.objectKey());
        rejectSymlinkPath(path);
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) metadata.sizeBytes());
            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes || total > metadata.sizeBytes()) {
                    throw new ArtifactIntegrityException();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (total != metadata.sizeBytes() || !actualHash.equals(metadata.sha256())) {
                throw new ArtifactIntegrityException();
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("ARTIFACT_READ_FAILED", exception);
        }
    }

    public void deleteExpired(UUID artifactId) {
        Metadata metadata = findMetadata(artifactId);
        Instant now = clock.instant();
        if (metadata.retentionClass() == RetentionClass.GROUND_TRUTH
                || metadata.expiresAt() == null || metadata.expiresAt().isAfter(now)
                || hasProtectedReference(artifactId)) {
            throw new ArtifactDeleteProtectedException();
        }
        if (metadata.lifecycleStatus() == LifecycleStatus.AVAILABLE) {
            transition(artifactId, LifecycleStatus.AVAILABLE, LifecycleStatus.DELETE_PENDING);
        } else if (metadata.lifecycleStatus() == LifecycleStatus.DELETED) {
            return;
        }
        Path path = resolveObjectKey(metadata.objectKey());
        rejectSymlinkPath(path.getParent());
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("ARTIFACT_OBJECT_DELETE_FAILED", exception);
        }
        transition(artifactId, LifecycleStatus.DELETE_PENDING, LifecycleStatus.DELETED);
    }

    public List<ReconciliationIssue> reconcile() {
        List<ReconciliationIssue> issues = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT artifact_id, object_key, sha256, size_bytes, lifecycle_status
                     FROM opspilot.artifact WHERE storage_provider = ? AND lifecycle_status <> 'DELETED'
                     """)) {
            statement.setString(1, PROVIDER);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = result.getObject(1, UUID.class);
                    Path path = resolveObjectKey(result.getString(2));
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        issues.add(new ReconciliationIssue(id, IssueType.METADATA_WITHOUT_OBJECT));
                    } else {
                        DigestAndSize actual = digest(path);
                        if (actual.sizeBytes() != result.getLong(4)
                                || !actual.sha256().equals(result.getString(3))) {
                            issues.add(new ReconciliationIssue(id, IssueType.HASH_OR_SIZE_DRIFT));
                        }
                    }
                }
            }
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("ARTIFACT_RECONCILIATION_FAILED", exception);
        }
        try (var paths = Files.walk(root)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().startsWith(".artifact-"))
                    .forEach(path -> {
                        String key = root.relativize(path).toString().replace('\\', '/');
                        if (!metadataExists(key)) {
                            issues.add(new ReconciliationIssue(null, IssueType.OBJECT_WITHOUT_METADATA));
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("ARTIFACT_RECONCILIATION_FAILED", exception);
        }
        return List.copyOf(issues);
    }

    Path resolveObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ArtifactPathException();
        }
        Path relative = Path.of(objectKey);
        if (relative.isAbsolute()) {
            throw new ArtifactPathException();
        }
        for (Path segment : relative) {
            String value = segment.toString();
            if (value.equals("..") || value.equals(".") || WINDOWS_DEVICE.matcher(value).matches()) {
                throw new ArtifactPathException();
            }
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new ArtifactPathException();
        }
        rejectSymlinkPath(resolved.getParent());
        return resolved;
    }

    private DigestAndSize writeTemporary(InputStream input, Path temporary) throws IOException {
        Objects.requireNonNull(input, "content");
        MessageDigest digest = sha256();
        long total = 0;
        try (input; var output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new ArtifactSizeLimitException();
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new DigestAndSize(HexFormat.of().formatHex(digest.digest()), total);
    }

    private static DigestAndSize digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                digest.update(buffer, 0, read);
            }
        }
        return new DigestAndSize(HexFormat.of().formatHex(digest.digest()), total);
    }

    private void insertMetadata(Metadata metadata) {
        String sql = """
                INSERT INTO opspilot.artifact
                    (artifact_id, run_id, task_id, storage_provider, object_key, uri, sha256,
                     size_bytes, media_type, access_level, lifecycle_status, retention_class,
                     expires_at, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{"schemaVersion":"1.0.0"}'::jsonb)
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, metadata.artifactId());
            statement.setObject(2, metadata.runId());
            statement.setObject(3, metadata.taskId());
            statement.setString(4, metadata.storageProvider());
            statement.setString(5, metadata.objectKey());
            statement.setString(6, metadata.uri());
            statement.setString(7, metadata.sha256());
            statement.setLong(8, metadata.sizeBytes());
            statement.setString(9, metadata.mediaType());
            statement.setString(10, metadata.accessLevel().name());
            statement.setString(11, metadata.lifecycleStatus().name());
            statement.setString(12, metadata.retentionClass().name());
            if (metadata.expiresAt() == null) {
                statement.setTimestamp(13, null);
            } else {
                statement.setTimestamp(13, Timestamp.from(metadata.expiresAt()));
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new ArtifactConflictException();
            }
            throw new IllegalStateException("ARTIFACT_METADATA_WRITE_FAILED", exception);
        }
    }

    private Metadata findMetadata(UUID artifactId) {
        String sql = """
                SELECT artifact_id, run_id, task_id, storage_provider, object_key, uri, sha256,
                       size_bytes, media_type, access_level, lifecycle_status, retention_class, expires_at
                FROM opspilot.artifact WHERE artifact_id = ?
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, artifactId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ArtifactNotFoundException();
                }
                return new Metadata(
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getObject(3, UUID.class), result.getString(4), result.getString(5),
                        result.getString(6), result.getString(7), result.getLong(8), result.getString(9),
                        AccessLevel.valueOf(result.getString(10)), LifecycleStatus.valueOf(result.getString(11)),
                        RetentionClass.valueOf(result.getString(12)),
                        result.getTimestamp(13) == null ? null : result.getTimestamp(13).toInstant());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("ARTIFACT_METADATA_READ_FAILED", exception);
        }
    }

    private boolean hasProtectedReference(UUID artifactId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM opspilot.evidence WHERE artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.evaluation_result WHERE report_artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.rca_report WHERE report_artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.observation_batch WHERE artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.code_snapshot WHERE manifest_artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.knowledge_document_version WHERE source_artifact_id = ?
                    UNION ALL SELECT 1 FROM opspilot.knowledge_chunk WHERE content_artifact_id = ?
                )
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= 7; index++) {
                statement.setObject(index, artifactId);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("ARTIFACT_REFERENCE_CHECK_FAILED", exception);
        }
    }

    private void transition(UUID artifactId, LifecycleStatus expected, LifecycleStatus next) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE opspilot.artifact SET lifecycle_status = ?
                     WHERE artifact_id = ? AND lifecycle_status = ?
                     """)) {
            statement.setString(1, next.name());
            statement.setObject(2, artifactId);
            statement.setString(3, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("ARTIFACT_LIFECYCLE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("ARTIFACT_LIFECYCLE_UPDATE_FAILED", exception);
        }
    }

    private boolean metadataExists(String objectKey) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM opspilot.artifact
                     WHERE storage_provider = ? AND object_key = ? AND lifecycle_status <> 'DELETED'
                     """)) {
            statement.setString(1, PROVIDER);
            statement.setString(2, objectKey);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("ARTIFACT_METADATA_READ_FAILED", exception);
        }
    }

    private static Path initializeRoot(Path configured) {
        Objects.requireNonNull(configured, "root");
        Path normalized = configured.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
                throw new ArtifactPathException();
            }
            Files.createDirectories(normalized);
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("ARTIFACT_ROOT_INVALID", exception);
        }
    }

    private static void rejectSymlinkPath(Path path) {
        if (path == null) {
            return;
        }
        Path current = path.toAbsolutePath().getRoot();
        for (Path segment : path.toAbsolutePath()) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new ArtifactPathException();
            }
        }
    }

    private static Instant expiresAt(Instant createdAt, RetentionClass retentionClass) {
        Duration retention = switch (retentionClass) {
            case INCIDENT_RUN, AUDIT -> Duration.ofDays(30);
            case RAW_OBSERVATION -> Duration.ofDays(14);
            case RCA, EVALUATION -> Duration.ofDays(90);
            case GROUND_TRUTH -> null;
        };
        return retention == null ? null : createdAt.plus(retention);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum AccessLevel { RUN_PRIVATE, TASK_PRIVATE, INTERNAL, EVALUATION, GROUND_TRUTH }
    public enum LifecycleStatus { AVAILABLE, DELETE_PENDING, DELETED }
    public enum RetentionClass { INCIDENT_RUN, AUDIT, RAW_OBSERVATION, RCA, EVALUATION, GROUND_TRUTH }
    public enum IssueType { OBJECT_WITHOUT_METADATA, METADATA_WITHOUT_OBJECT, HASH_OR_SIZE_DRIFT }

    public record WriteRequest(
            UUID artifactId, UUID runId, UUID taskId, String mediaType,
            AccessLevel accessLevel, RetentionClass retentionClass, InputStream content) {
        public WriteRequest {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(accessLevel, "accessLevel");
            Objects.requireNonNull(retentionClass, "retentionClass");
            Objects.requireNonNull(content, "content");
        }
    }

    public record AccessContext(UUID runId, UUID taskId, boolean evaluation, boolean groundTruth) { }

    public record Metadata(
            UUID artifactId, UUID runId, UUID taskId, String storageProvider, String objectKey,
            String uri, String sha256, long sizeBytes, String mediaType, AccessLevel accessLevel,
            LifecycleStatus lifecycleStatus, RetentionClass retentionClass, Instant expiresAt) { }

    public record ReconciliationIssue(UUID artifactId, IssueType type) { }
    private record DigestAndSize(String sha256, long sizeBytes) { }

    @FunctionalInterface
    public interface AccessAuthorizer {
        boolean allowed(AccessContext context, Metadata metadata);

        static AccessAuthorizer sameRun() {
            return (context, metadata) -> context != null
                    && context.runId() != null
                    && context.runId().equals(metadata.runId())
                    && (metadata.accessLevel() != AccessLevel.TASK_PRIVATE
                        || Objects.equals(context.taskId(), metadata.taskId()))
                    && (metadata.accessLevel() != AccessLevel.EVALUATION || context.evaluation())
                    && (metadata.accessLevel() != AccessLevel.GROUND_TRUTH || context.groundTruth());
        }
    }

    public static final class ArtifactPathException extends RuntimeException {
        public ArtifactPathException() { super("ARTIFACT_PATH_INVALID"); }
    }
    public static final class ArtifactConflictException extends RuntimeException {
        public ArtifactConflictException() { super("ARTIFACT_IMMUTABLE_CONFLICT"); }
    }
    public static final class ArtifactAccessDeniedException extends RuntimeException {
        public ArtifactAccessDeniedException() { super("ARTIFACT_ACCESS_DENIED"); }
    }
    public static final class ArtifactIntegrityException extends RuntimeException {
        public ArtifactIntegrityException() { super("ARTIFACT_INTEGRITY_FAILED"); }
    }
    public static final class ArtifactSizeLimitException extends RuntimeException {
        public ArtifactSizeLimitException() { super("ARTIFACT_SIZE_LIMIT_EXCEEDED"); }
    }
    public static final class ArtifactDeleteProtectedException extends RuntimeException {
        public ArtifactDeleteProtectedException() { super("ARTIFACT_DELETE_PROTECTED"); }
    }
    public static final class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException() { super("ARTIFACT_NOT_FOUND"); }
    }
}
