package io.github.opspilot.core.application.provider;

import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Field-level validation for enabled model profiles before typed effective configuration is built. */
public final class ModelProfileValidation {
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private ModelProfileValidation() {
    }

    public record EnabledProfileCandidate(
            String fieldPath,
            String provider,
            String baseUrl,
            String secretRef,
            String model,
            Integer contextWindowTokens,
            Integer maxAttempts,
            Integer concurrencyLimit,
            Integer maxOutputTokensPerCall,
            Set<ModelCapability> declaredCapabilities,
            Set<ModelCapability> requiredCapabilities) {
        public EnabledProfileCandidate {
            fieldPath = fieldPath == null || fieldPath.isBlank() ? "modelProfile" : fieldPath;
            if (declaredCapabilities != null) {
                declaredCapabilities = Set.copyOf(declaredCapabilities);
            }
            requiredCapabilities = requiredCapabilities == null
                    ? Set.of()
                    : Set.copyOf(requiredCapabilities);
        }
    }

    public record ConfigurationError(String fieldPath, String code, String requiredInput) {
        public ConfigurationError {
            Objects.requireNonNull(fieldPath, "fieldPath");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(requiredInput, "requiredInput");
        }

        String safeDescription() {
            return fieldPath + ":" + code + "[required=" + requiredInput + "]";
        }
    }

    public static void validate(EnabledProfileCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<ConfigurationError> errors = new ArrayList<>();
        requiredText(errors, candidate.fieldPath() + ".provider", candidate.provider(), "stable provider ID");
        validateBaseUrl(errors, candidate.fieldPath() + ".baseUrl", candidate.baseUrl());
        validateSecretRef(errors, candidate.fieldPath() + ".secretRef", candidate.secretRef());
        requiredText(errors, candidate.fieldPath() + ".model", candidate.model(), "explicit model ID");
        positive(errors, candidate.fieldPath() + ".contextWindowTokens",
                candidate.contextWindowTokens(), "positive integer");
        positive(errors, candidate.fieldPath() + ".maxAttempts", candidate.maxAttempts(), "positive integer");
        positive(errors, candidate.fieldPath() + ".concurrencyLimit",
                candidate.concurrencyLimit(), "positive integer");
        positive(errors, candidate.fieldPath() + ".maxOutputTokensPerCall",
                candidate.maxOutputTokensPerCall(), "positive integer");
        if (candidate.declaredCapabilities() == null
                || !candidate.declaredCapabilities().containsAll(candidate.requiredCapabilities())) {
            errors.add(new ConfigurationError(candidate.fieldPath() + ".capabilities",
                    "REQUIRED_CAPABILITY_MISSING", "all required capabilities"));
        }
        if (candidate.contextWindowTokens() != null && candidate.contextWindowTokens() > 0
                && candidate.maxOutputTokensPerCall() != null
                && candidate.maxOutputTokensPerCall() > candidate.contextWindowTokens()) {
            errors.add(new ConfigurationError(candidate.fieldPath() + ".maxOutputTokensPerCall",
                    "OUTPUT_LIMIT_EXCEEDS_CONTEXT", "value not greater than contextWindowTokens"));
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(ConfigurationError::fieldPath)
                    .thenComparing(ConfigurationError::code));
            throw new ConfigurationException(errors);
        }
    }

    private static void validateBaseUrl(List<ConfigurationError> errors, String path, String value) {
        if (value == null || value.isBlank()) {
            errors.add(new ConfigurationError(path, "REQUIRED_FIELD_MISSING", "absolute http(s) base URL"));
            return;
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                errors.add(new ConfigurationError(path, "BASE_URL_INVALID", "absolute http(s) URL without credentials, query, or fragment"));
            }
        } catch (URISyntaxException exception) {
            errors.add(new ConfigurationError(path, "BASE_URL_INVALID", "absolute http(s) URL without credentials, query, or fragment"));
        }
    }

    private static void validateSecretRef(List<ConfigurationError> errors, String path, String value) {
        if (value == null || value.isBlank()) {
            errors.add(new ConfigurationError(path, "REQUIRED_FIELD_MISSING", "env:NAME or file:/absolute/path"));
            return;
        }
        if (value.startsWith("env:") && ENV_NAME.matcher(value.substring(4)).matches()) {
            return;
        }
        if (value.startsWith("file:") && isAbsolutePath(value.substring(5))) {
            return;
        }
        errors.add(new ConfigurationError(path, "SECRET_REF_INVALID", "env:NAME or file:/absolute/path"));
    }

    private static boolean isAbsolutePath(String value) {
        try {
            return !value.isBlank() && Path.of(value).isAbsolute();
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private static void requiredText(List<ConfigurationError> errors, String path, String value, String required) {
        if (value == null || value.isBlank()) {
            errors.add(new ConfigurationError(path, "REQUIRED_FIELD_MISSING", required));
        }
    }

    private static void positive(List<ConfigurationError> errors, String path, Integer value, String required) {
        if (value == null || value <= 0) {
            errors.add(new ConfigurationError(path, "POSITIVE_VALUE_REQUIRED", required));
        }
    }

    public static final class ConfigurationException extends RuntimeException {
        private final List<ConfigurationError> errors;

        private ConfigurationException(List<ConfigurationError> errors) {
            super("MODEL_PROFILE_CONFIGURATION_INVALID:" + errors.stream()
                    .map(ConfigurationError::safeDescription).toList());
            this.errors = List.copyOf(errors);
        }

        public List<ConfigurationError> errors() {
            return errors;
        }
    }
}
