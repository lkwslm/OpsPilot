package io.github.opspilot.adapters.sandbox.maven;

import io.github.opspilot.core.port.sandbox.SandboxRunnerPort;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit sandbox registry with version compatibility and freeze. */
public final class MavenSandboxRegistry {
    private final Map<String, Registration> runners = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(String id, String version, SandboxRunnerPort runner) {
        if (frozen) throw failure("SANDBOX_REGISTRY_FROZEN", id);
        if (runners.putIfAbsent(id, new Registration(version, runner)) != null) throw failure("DUPLICATE_SANDBOX_ID", id);
    }

    public synchronized void freeze(Map<String, String> required) {
        if (frozen) throw failure("SANDBOX_REGISTRY_FROZEN", "registry");
        for (Map.Entry<String, String> entry : required.entrySet()) {
            Registration actual = runners.get(entry.getKey());
            if (actual == null) throw failure("SANDBOX_MISSING", entry.getKey());
            if (!actual.version().equals(entry.getValue())) throw failure("SANDBOX_VERSION_INCOMPATIBLE", entry.getKey());
        }
        frozen = true;
    }

    public synchronized SandboxRunnerPort require(String id, String version) {
        if (!frozen) throw failure("SANDBOX_REGISTRY_NOT_FROZEN", id);
        Registration value = runners.get(id);
        if (value == null || !value.version().equals(version)) throw failure("SANDBOX_NOT_AVAILABLE", id);
        return value.runner();
    }

    private record Registration(String version, SandboxRunnerPort runner) { }
    private static RegistryException failure(String code, String id) { return new RegistryException(code + ":" + id, code); }
    public static final class RegistryException extends RuntimeException {
        private final String code;
        RegistryException(String message, String code) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
