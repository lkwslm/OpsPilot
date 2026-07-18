package io.github.opspilot.adapters.observability;

import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(password|passwd|token|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;]+" );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+" );

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        return BEARER.matcher(KEY_VALUE.matcher(value).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }
}
