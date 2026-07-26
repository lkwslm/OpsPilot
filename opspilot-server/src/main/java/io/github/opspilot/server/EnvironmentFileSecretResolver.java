package io.github.opspilot.server;

import io.github.opspilot.core.port.provider.SecretResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Resolves env: and file: references at the provider invocation boundary. */
public final class EnvironmentFileSecretResolver implements SecretResolver {
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private final Function<String, String> environment;
    private final SecretFileReader files;

    public EnvironmentFileSecretResolver(
            Function<String, String> environment, SecretFileReader files) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.files = Objects.requireNonNull(files, "files");
    }

    public static EnvironmentFileSecretResolver system() {
        return new EnvironmentFileSecretResolver(System::getenv, Files::readString);
    }

    @Override
    public ResolvedSecret resolve(String secretRef, ResolutionContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.authorized()) {
            throw new SecretResolutionException("SECRET_ACCESS_DENIED");
        }
        if (secretRef == null || secretRef.isBlank() || !secretRef.contains(":")) {
            throw new SecretResolutionException("SECRET_REF_FORMAT_INVALID");
        }
        int separator = secretRef.indexOf(':');
        String scheme = secretRef.substring(0, separator);
        String reference = secretRef.substring(separator + 1);
        return switch (scheme) {
            case "env" -> resolveEnvironment(reference);
            case "file" -> resolveFile(reference);
            default -> throw new SecretResolutionException("SECRET_REF_SCHEME_UNSUPPORTED:" + scheme);
        };
    }

    private ResolvedSecret resolveEnvironment(String name) {
        if (!ENV_NAME.matcher(name).matches()) {
            throw new SecretResolutionException("SECRET_ENV_NAME_INVALID");
        }
        return resolved(environment.apply(name), "SECRET_VALUE_MISSING:env:" + name);
    }

    private ResolvedSecret resolveFile(String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException invalidPath) {
            throw new SecretResolutionException("SECRET_FILE_PATH_INVALID");
        }
        if (!path.isAbsolute()) {
            throw new SecretResolutionException("SECRET_FILE_PATH_NOT_ABSOLUTE");
        }
        try {
            return resolved(files.read(path), "SECRET_VALUE_MISSING:file:" + path);
        } catch (IOException exception) {
            throw new SecretResolutionException("SECRET_FILE_READ_FAILED");
        }
    }

    private static ResolvedSecret resolved(String rawValue, String missingCode) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new SecretResolutionException(missingCode);
        }
        char[] value = rawValue.strip().toCharArray();
        try {
            return new ResolvedSecret(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @FunctionalInterface
    public interface SecretFileReader {
        String read(Path path) throws IOException;
    }

    public static final class SecretResolutionException extends RuntimeException {
        public SecretResolutionException(String code) {
            super(code);
        }
    }
}
