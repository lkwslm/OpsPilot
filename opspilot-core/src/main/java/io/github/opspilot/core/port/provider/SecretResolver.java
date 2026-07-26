package io.github.opspilot.core.port.provider;

import java.util.Arrays;

/** Resolves a supported secret reference only at an authorized provider invocation boundary. */
@FunctionalInterface
public interface SecretResolver {
    ResolvedSecret resolve(String secretRef, ResolutionContext context);

    record ResolutionContext(String actorId, boolean authorized) {
    }

    /** Must never be persisted, logged, traced, or included in a model context. */
    record ResolvedSecret(char[] value) implements AutoCloseable {
        public ResolvedSecret {
            value = value == null ? new char[0] : value.clone();
        }

        @Override
        public char[] value() {
            return value.clone();
        }

        @Override
        public void close() {
            Arrays.fill(value, '\0');
        }

        @Override
        public String toString() {
            return "ResolvedSecret[REDACTED]";
        }
    }
}
