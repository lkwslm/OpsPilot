package io.github.opspilot.core.application.correlation;

import java.util.regex.Pattern;

/** Bounded error/log projection that removes credentials, URL queries, prompts and stack frames. */
public final class StableErrorSanitizer {
    public static final int INLINE_LIMIT = 2048;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:authorization|secret|password|token|api[_-]?key|credential)"
                    + "\\s*[=:]\\s*|bearer\\s+)[^\\s,;]+"
    );
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?]+)\\?[^\\s]+");
    private static final Pattern PROMPT = Pattern.compile("(?is)(prompt|messages?)\\s*[=:]\\s*.*");
    private static final Pattern STACK = Pattern.compile("(?s)\\s+(at|Caused by:)\\s+.*");

    private StableErrorSanitizer() { }

    public static Projection project(String raw) {
        String value = raw == null ? "Unavailable" : raw;
        boolean artifactRequired = value.length() > INLINE_LIMIT || SECRET.matcher(value).find()
                || PROMPT.matcher(value).find();
        value = SECRET.matcher(value).replaceAll("$1[REDACTED]");
        value = URL_QUERY.matcher(value).replaceAll("$1?[REDACTED]");
        value = PROMPT.matcher(value).replaceAll("$1=[REDACTED]");
        value = STACK.matcher(value).replaceAll("");
        value = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (value.length() > 256) value = value.substring(0, 256);
        return new Projection(value, artifactRequired);
    }

    public record Projection(String summary, boolean artifactRequired) { }
}
