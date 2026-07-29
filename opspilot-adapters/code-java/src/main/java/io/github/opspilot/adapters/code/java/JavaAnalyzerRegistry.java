package io.github.opspilot.adapters.code.java;

import io.github.opspilot.core.port.code.CodeAnalysisPort;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit analyzer registry; membership cannot change after validation. */
public final class JavaAnalyzerRegistry {
    private final Map<String, Registration> analyzers = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(String id, String version, CodeAnalysisPort analyzer) {
        if (frozen) throw failure("ANALYZER_REGISTRY_FROZEN", id);
        if (analyzers.putIfAbsent(id, new Registration(version, analyzer)) != null) {
            throw failure("DUPLICATE_ANALYZER_ID", id);
        }
    }

    public synchronized void freeze(Map<String, String> required) {
        if (frozen) throw failure("ANALYZER_REGISTRY_FROZEN", "registry");
        for (Map.Entry<String, String> entry : required.entrySet()) {
            Registration actual = analyzers.get(entry.getKey());
            if (actual == null) throw failure("ANALYZER_MISSING", entry.getKey());
            if (!actual.version().equals(entry.getValue())) throw failure("ANALYZER_VERSION_INCOMPATIBLE", entry.getKey());
        }
        frozen = true;
    }

    public synchronized CodeAnalysisPort require(String id, String version) {
        if (!frozen) throw failure("ANALYZER_REGISTRY_NOT_FROZEN", id);
        Registration registration = analyzers.get(id);
        if (registration == null || !registration.version().equals(version)) throw failure("ANALYZER_NOT_AVAILABLE", id);
        return registration.analyzer();
    }

    private static RegistryException failure(String code, String id) { return new RegistryException(code, id); }
    private record Registration(String version, CodeAnalysisPort analyzer) { }
    public static final class RegistryException extends RuntimeException {
        private final String code;
        RegistryException(String code, String id) { super(code + ":" + id); this.code = code; }
        public String code() { return code; }
    }
}
