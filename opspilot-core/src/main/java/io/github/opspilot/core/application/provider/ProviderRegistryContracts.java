package io.github.opspilot.core.application.provider;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared value contracts for the three type-specific provider registries. */
public final class ProviderRegistryContracts {
    private ProviderRegistryContracts() {
    }

    public enum CapabilityKind {
        CHAT,
        EMBEDDING,
        RERANK
    }

    public record ProtocolVersion(int major, int minor) {
        public ProtocolVersion {
            if (major < 0 || minor < 0) {
                throw new IllegalArgumentException("protocol version components must be non-negative");
            }
        }

        public boolean supports(ProtocolVersion required) {
            Objects.requireNonNull(required, "required");
            return major == required.major && minor >= required.minor;
        }

        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

    public record ProviderCapabilityKey(
            CapabilityKind kind,
            String capability,
            String stableProviderId,
            String protocol,
            ProtocolVersion protocolVersion,
            String model,
            String modelRevision) {

        public ProviderCapabilityKey {
            Objects.requireNonNull(kind, "kind");
            capability = requireText(capability, "capability");
            stableProviderId = requireText(stableProviderId, "stableProviderId");
            protocol = requireText(protocol, "protocol");
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            model = requireText(model, "model");
            modelRevision = requireText(modelRevision, "modelRevision");
        }

        public boolean satisfies(ProviderCapabilityKey required) {
            Objects.requireNonNull(required, "required");
            return kind == required.kind
                    && capability.equals(required.capability)
                    && stableProviderId.equals(required.stableProviderId)
                    && protocol.equals(required.protocol)
                    && protocolVersion.supports(required.protocolVersion)
                    && model.equals(required.model)
                    && modelRevision.equals(required.modelRevision);
        }

        String canonicalValue() {
            return kind + ":" + capability + ":" + stableProviderId + ":" + protocol + ":"
                    + protocolVersion + ":" + model + ":" + modelRevision;
        }
    }

    public record ProviderRequirement(String stableProviderId, Set<ProviderCapabilityKey> capabilities) {
        public ProviderRequirement {
            stableProviderId = requireText(stableProviderId, "stableProviderId");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            if (capabilities.isEmpty()) {
                throw new IllegalArgumentException("required provider must declare at least one capability");
            }
            String requiredProviderId = stableProviderId;
            if (capabilities.stream().anyMatch(key -> !key.stableProviderId().equals(requiredProviderId))) {
                throw new IllegalArgumentException("required capability provider ID must match requirement provider ID");
            }
        }
    }

    public static final class ProviderRegistryException extends RuntimeException {
        private final String code;
        private final String registryName;
        private final String stableProviderId;

        ProviderRegistryException(String code, String registryName, String stableProviderId, String detail) {
            super(code + "[registry=" + registryName + ",provider=" + stableProviderId + ",detail=" + detail + "]");
            this.code = code;
            this.registryName = registryName;
            this.stableProviderId = stableProviderId;
        }

        public String code() {
            return code;
        }

        public String registryName() {
            return registryName;
        }

        public String stableProviderId() {
            return stableProviderId;
        }
    }

    static void validateRegistration(
            String registryName,
            CapabilityKind expectedKind,
            String stableProviderId,
            Set<ProviderCapabilityKey> capabilities) {
        requireText(stableProviderId, "stableProviderId");
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw failure("PROVIDER_CAPABILITIES_EMPTY", registryName, stableProviderId, "no capabilities declared");
        }
        capabilities.stream().sorted(Comparator.comparing(ProviderCapabilityKey::canonicalValue)).forEach(key -> {
            if (!key.stableProviderId().equals(stableProviderId)) {
                throw failure("CAPABILITY_PROVIDER_ID_MISMATCH", registryName, stableProviderId,
                        key.canonicalValue());
            }
            if (key.kind() != expectedKind) {
                throw failure("CAPABILITY_KIND_MISMATCH", registryName, stableProviderId,
                        "expected=" + expectedKind + ",actual=" + key.kind());
            }
        });
    }

    static void validateRequirements(
            String registryName,
            CapabilityKind expectedKind,
            Map<String, Set<ProviderCapabilityKey>> registeredCapabilities,
            Set<ProviderRequirement> requirements) {
        Objects.requireNonNull(requirements, "requirements").stream()
                .sorted(Comparator.comparing(ProviderRequirement::stableProviderId))
                .forEach(requirement -> {
                    Set<ProviderCapabilityKey> actual = registeredCapabilities.get(requirement.stableProviderId());
                    if (actual == null) {
                        throw failure("REQUIRED_PROVIDER_MISSING", registryName,
                                requirement.stableProviderId(), "provider not registered");
                    }
                    requirement.capabilities().stream()
                            .sorted(Comparator.comparing(ProviderCapabilityKey::canonicalValue))
                            .forEach(required -> {
                                if (required.kind() != expectedKind
                                        || actual.stream().noneMatch(key -> key.satisfies(required))) {
                                    throw failure("REQUIRED_CAPABILITY_INCOMPATIBLE", registryName,
                                            requirement.stableProviderId(), required.canonicalValue());
                                }
                            });
                });
    }

    static ProviderRegistryException failure(
            String code, String registryName, String stableProviderId, String detail) {
        return new ProviderRegistryException(code, registryName, stableProviderId, detail);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
