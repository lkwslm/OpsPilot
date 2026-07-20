package io.github.opspilot.core.domain.value;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated scalar values shared by state, repository, and protocol boundaries. */
public final class DomainValues {
    private static final Pattern SHA_256 = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern ROOT_CAUSE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$");
    public static final Set<String> MVP_ROOT_CAUSES = Set.of(
            "dependency.latency.inventory",
            "database.pool.exhausted.order",
            "service.instance.stopped.inventory");

    private DomainValues() {
    }

    public record Sha256(String value) {
        public Sha256 {
            if (value == null || !SHA_256.matcher(value).matches()) {
                throw new IllegalArgumentException("hash must be lowercase sha256:<64 hex>");
            }
        }
    }

    public record RootCauseCode(String value) {
        public RootCauseCode {
            if (value == null || !ROOT_CAUSE.matcher(value).matches()) {
                throw new IllegalArgumentException("rootCauseCode must be lowercase dotted notation");
            }
        }

        public static RootCauseCode known(String value, Set<String> catalog) {
            Objects.requireNonNull(catalog, "catalog");
            RootCauseCode code = new RootCauseCode(value);
            if (!catalog.contains(code.value)) {
                throw new IllegalArgumentException("unknown rootCauseCode");
            }
            return code;
        }

        /** An inconclusive or unknown diagnosis is represented by Optional.empty(), never a temporary code. */
        public static Optional<RootCauseCode> resolve(String candidate, Set<String> catalog) {
            if (candidate == null || !ROOT_CAUSE.matcher(candidate).matches() || !catalog.contains(candidate)) {
                return Optional.empty();
            }
            return Optional.of(new RootCauseCode(candidate));
        }
    }
}
