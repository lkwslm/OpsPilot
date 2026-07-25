package io.github.opspilot.core.port.observability;

/** Resolves a controlled connectionRef only at the adapter execution boundary. */
@FunctionalInterface
public interface SecretResolver {
    ResolvedConnection resolve(String connectionRef, ResolutionContext context);

    record ResolutionContext(String actorId, String targetSystemId, boolean authorized) { }

    /** Must never be persisted, logged, or exposed to a model context. */
    record ResolvedConnection(String endpoint, char[] secret) implements AutoCloseable {
        public ResolvedConnection {
            secret = secret == null ? new char[0] : secret.clone();
        }
        @Override public char[] secret() { return secret.clone(); }
        @Override public void close() { java.util.Arrays.fill(secret, '\0'); }
        @Override public String toString() { return "ResolvedConnection[REDACTED]"; }
    }
}
