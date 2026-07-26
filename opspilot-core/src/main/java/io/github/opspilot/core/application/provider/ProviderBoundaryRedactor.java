package io.github.opspilot.core.application.provider;

import java.util.regex.Pattern;

/** Fixed redaction applied before provider output or failures cross the adapter boundary. */
public final class ProviderBoundaryRedactor {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(password|passwd|token|api[_-]?key|authorization|secret)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+");

    private ProviderBoundaryRedactor() {
    }

    public static String redact(String value, char[] resolvedSecret) {
        if (value == null) {
            return null;
        }
        String redacted = BEARER.matcher(KEY_VALUE.matcher(value).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
        if (resolvedSecret != null && resolvedSecret.length > 0) {
            String exactSecret = new String(resolvedSecret);
            redacted = redacted.replace(exactSecret, "[REDACTED]");
        }
        return redacted;
    }

    public static String failureSummary(Throwable failure, char[] resolvedSecret) {
        if (failure != null) {
            redact(failure.getMessage(), resolvedSecret);
        }
        return "provider call failed";
    }
}
