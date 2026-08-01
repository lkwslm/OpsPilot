package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

final class InfinityRerankDtos {
    private InfinityRerankDtos() {
    }

    record Request(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            @JsonProperty("return_documents") boolean returnDocuments) {
        Request {
            documents = List.copyOf(documents);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String model, List<Result> results, Usage usage) {
        Response {
            results = results == null ? null : List.copyOf(results);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(int index, @JsonProperty("relevance_score") Double relevanceScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("total_tokens") long totalTokens) {
    }
}
