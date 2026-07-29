package io.github.opspilot.core.application.code;

import io.github.opspilot.core.port.code.CodeContracts.SourceKind;
import io.github.opspilot.core.port.code.CodeSourcePort;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Dedicated explicit registry for code-hosting adapters. */
public final class CodeSourceAdapterRegistry {
    private final Map<SourceKind, Registration> adapters = new EnumMap<>(SourceKind.class);
    private boolean frozen;

    public synchronized void register(SourceKind kind, String adapterId, String version, CodeSourcePort adapter) {
        if (frozen) throw failure("CODE_SOURCE_REGISTRY_FROZEN", kind);
        Objects.requireNonNull(kind, "kind");
        if (adapterId == null || adapterId.isBlank() || version == null || version.isBlank()) {
            throw failure("CODE_SOURCE_DESCRIPTOR_INVALID", kind);
        }
        if (adapters.putIfAbsent(kind, new Registration(adapterId, version, Objects.requireNonNull(adapter, "adapter"))) != null) {
            throw failure("DUPLICATE_CODE_SOURCE_ADAPTER", kind);
        }
    }

    public synchronized void freeze(Map<SourceKind, String> requiredVersions) {
        if (frozen) throw failure("CODE_SOURCE_REGISTRY_FROZEN", null);
        for (Map.Entry<SourceKind, String> required : requiredVersions.entrySet()) {
            Registration actual = adapters.get(required.getKey());
            if (actual == null) throw failure("CODE_SOURCE_ADAPTER_MISSING", required.getKey());
            if (!actual.version().equals(required.getValue())) {
                throw failure("CODE_SOURCE_ADAPTER_VERSION_INCOMPATIBLE", required.getKey());
            }
        }
        frozen = true;
    }

    public synchronized Registration require(SourceKind kind) {
        if (!frozen) throw failure("CODE_SOURCE_REGISTRY_NOT_FROZEN", kind);
        Registration adapter = adapters.get(kind);
        if (adapter == null) throw failure("CODE_SOURCE_ADAPTER_MISSING", kind);
        return adapter;
    }

    public record Registration(String adapterId, String version, CodeSourcePort adapter) { }

    private static RegistryException failure(String code, SourceKind kind) {
        return new RegistryException(code, kind);
    }

    public static final class RegistryException extends RuntimeException {
        private final String code;
        private final SourceKind kind;
        RegistryException(String code, SourceKind kind) {
            super(code + ":" + kind);
            this.code = code;
            this.kind = kind;
        }
        public String code() { return code; }
        public SourceKind kind() { return kind; }
    }
}
