package io.github.opspilot.core.port.provider;

import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;

import java.time.Instant;
import java.util.List;

public interface EmbeddingPort {
    ProviderResult<List<float[]>> embed(EmbeddingRequest request);

    record EmbeddingRequest(ProviderIdentity identity, Instant deadline, List<String> inputs) {
        public EmbeddingRequest { inputs = List.copyOf(inputs); }
    }
}
