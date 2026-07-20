package io.github.opspilot.core.port.provider;

import java.util.Objects;

/** Vendor-neutral provider identity, usage and failure envelope. */
public final class ProviderContracts {
    private ProviderContracts() {
    }

    public record ProviderIdentity(String providerId, String modelId, String revision) {
        public ProviderIdentity {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(modelId, "modelId");
        }
    }

    public record ProviderUsage(long inputTokens, long outputTokens, Long costMicros) {
    }

    public record ProviderFailure(String errorCode, boolean retryable, String redactedSummary) {
    }

    public record ProviderResult<T>(T value, ProviderUsage usage, ProviderFailure failure) {
        public ProviderResult {
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("provider result must contain exactly one of value or failure");
            }
        }
    }
}
