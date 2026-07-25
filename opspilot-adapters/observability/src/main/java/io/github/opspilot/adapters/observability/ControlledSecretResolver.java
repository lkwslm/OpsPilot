package io.github.opspilot.adapters.observability;

import io.github.opspilot.core.port.observability.SecretResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** In-process boundary for controlled connection references supplied by deployment configuration. */
public final class ControlledSecretResolver implements SecretResolver {
    private final Map<String, Binding> bindings;

    public ControlledSecretResolver(Map<String, Binding> bindings) {
        this.bindings = Map.copyOf(new LinkedHashMap<>(bindings));
    }

    @Override
    public ResolvedConnection resolve(String connectionRef, ResolutionContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.authorized()) throw new SecretResolutionException("SECRET_ACCESS_DENIED");
        Binding binding = bindings.get(connectionRef);
        if (binding == null) throw new SecretResolutionException("CONNECTION_REF_NOT_FOUND");
        if (!binding.targetSystemId().equals(context.targetSystemId())) {
            throw new SecretResolutionException("CONNECTION_REF_TARGET_MISMATCH");
        }
        return new ResolvedConnection(binding.endpoint(), binding.secret());
    }

    public record Binding(String targetSystemId, String endpoint, char[] secret) {
        public Binding {
            Objects.requireNonNull(targetSystemId, "targetSystemId");
            Objects.requireNonNull(endpoint, "endpoint");
            secret = secret == null ? new char[0] : secret.clone();
        }

        public static Binding withoutSecret(String targetSystemId, String endpoint) {
            return new Binding(targetSystemId, endpoint, new char[0]);
        }

        @Override public char[] secret() { return secret.clone(); }
        @Override public String toString() { return "Binding[REDACTED]"; }
    }

    public static final class SecretResolutionException extends RuntimeException {
        public SecretResolutionException(String code) { super(code); }
    }
}
