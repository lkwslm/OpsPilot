package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

final class InfinityEmbeddingDtos {
    private InfinityEmbeddingDtos() { }

    record EmbeddingRequest(String model, List<String> input, @JsonProperty("encoding_format") String encodingFormat) {
        EmbeddingRequest { input = List.copyOf(input); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(String model, List<EmbeddingData> data, Usage usage) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(int index, List<Double> embedding) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") long promptTokens,
                 @JsonProperty("total_tokens") long totalTokens) { }
}
