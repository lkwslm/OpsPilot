package io.github.opspilot.adapters.retrieval.infinity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Plans stable-order batches without truncating a single input. */
public final class InfinityEmbeddingBatcher {
    @FunctionalInterface
    public interface TokenCounter {
        int count(String text);
    }

    public record IndexedInput(int globalIndex, String text, int tokenCount) { }
    public record Batch(int firstGlobalIndex, List<IndexedInput> inputs, int tokenCount) {
        public Batch { inputs = List.copyOf(inputs); }
    }

    private final int maxItems;
    private final int maxTokens;
    private final TokenCounter tokens;

    public InfinityEmbeddingBatcher(int maxItems, int providerMaxItems, int maxTokens, TokenCounter tokens) {
        if (maxItems < 1 || providerMaxItems < 1 || maxTokens < 1) {
            throw new IllegalArgumentException("batch limits must be positive");
        }
        this.maxItems = Math.min(maxItems, providerMaxItems);
        this.maxTokens = maxTokens;
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    public List<Batch> plan(List<String> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty()) {
            throw new EmbeddingBatchException("EMBEDDING_INPUTS_EMPTY");
        }
        List<Batch> batches = new ArrayList<>();
        List<IndexedInput> current = new ArrayList<>();
        int currentTokens = 0;
        for (int index = 0; index < inputs.size(); index++) {
            String text = Objects.requireNonNull(inputs.get(index), "embedding input");
            int inputTokens = tokens.count(text);
            if (inputTokens < 0) {
                throw new IllegalStateException("token counter returned a negative value");
            }
            if (inputTokens > maxTokens) {
                throw new EmbeddingBatchException("EMBEDDING_INPUT_TOKEN_LIMIT_EXCEEDED");
            }
            if (!current.isEmpty()
                    && (current.size() == maxItems || currentTokens + inputTokens > maxTokens)) {
                batches.add(batch(current, currentTokens));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(new IndexedInput(index, text, inputTokens));
            currentTokens += inputTokens;
        }
        if (!current.isEmpty()) {
            batches.add(batch(current, currentTokens));
        }
        return List.copyOf(batches);
    }

    private static Batch batch(List<IndexedInput> inputs, int tokens) {
        return new Batch(inputs.getFirst().globalIndex(), inputs, tokens);
    }

    public static final class EmbeddingBatchException extends IllegalArgumentException {
        public EmbeddingBatchException(String code) { super(code); }
    }
}
