package io.github.opspilot.adapters.model.openai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/** Composes OpenAI-compatible resource URIs while preserving an existing version prefix. */
public final class OpenAiCompatibleUris {
    private OpenAiCompatibleUris() {
    }

    public static URI resource(URI baseUrl, String resourcePath) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (!baseUrl.isAbsolute() || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute server URI without query or fragment");
        }
        String scheme = baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("baseUrl scheme must be http or https");
        }

        String resource = trimSlashes(requireResourcePath(resourcePath));
        if (resource.equals("..") || resource.startsWith("../") || resource.contains("/../")) {
            throw new IllegalArgumentException("resourcePath must not traverse parent segments");
        }
        String basePath = trimTrailingSlash(baseUrl.getPath());
        boolean baseHasVersion = hasV1Suffix(basePath);
        boolean resourceHasVersion = resource.equals("v1") || resource.startsWith("v1/");
        if (baseHasVersion && resourceHasVersion) {
            resource = resource.length() == 2 ? "" : resource.substring(3);
        } else if (!baseHasVersion && !resourceHasVersion) {
            basePath = basePath + "/v1";
        }
        String path = basePath + (resource.isEmpty() ? "" : "/" + resource);
        try {
            return new URI(baseUrl.getScheme(), null, baseUrl.getHost(), baseUrl.getPort(), path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("resourcePath is not a valid URI path", exception);
        }
    }

    private static String requireResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        if (resourcePath.indexOf('?') >= 0 || resourcePath.indexOf('#') >= 0 || resourcePath.contains("://")) {
            throw new IllegalArgumentException("resourcePath must be a relative path without query or fragment");
        }
        return resourcePath;
    }

    private static boolean hasV1Suffix(String path) {
        return path.equals("/v1") || path.endsWith("/v1");
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank() || "/".equals(value)) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
