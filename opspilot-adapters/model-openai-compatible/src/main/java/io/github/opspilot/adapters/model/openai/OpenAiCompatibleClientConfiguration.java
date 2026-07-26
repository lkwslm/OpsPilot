package io.github.opspilot.adapters.model.openai;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Non-sensitive client configuration. A Secret reference is stored instead of an API key. */
public record OpenAiCompatibleClientConfiguration(URI baseUrl, String model, String secretRef) {
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.deepseek.com");

    public OpenAiCompatibleClientConfiguration {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || !baseUrl.isAbsolute()
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URI without user info");
        }
        model = nullIfBlank(model);
        secretRef = nullIfBlank(secretRef);
    }

    public static OpenAiCompatibleClientConfiguration defaults() {
        return new OpenAiCompatibleClientConfiguration(DEFAULT_BASE_URL, null, null);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
