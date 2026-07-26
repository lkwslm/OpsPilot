package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.DistanceMetric;
import io.github.opspilot.adapters.retrieval.infinity.InfinityEmbeddingConfiguration.Normalization;
import io.github.opspilot.adapters.retrieval.infinity.InfinityVectorValidator.VectorContractException;
import io.github.opspilot.core.port.provider.EmbeddingPort.EmbeddingRequest;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfinityEmbeddingAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void batcherHonorsTokenItemAndProviderLimitsWithoutLosingGlobalOrder() {
        InfinityEmbeddingBatcher batcher = new InfinityEmbeddingBatcher(4, 2, 5, String::length);

        var batches = batcher.plan(List.of("aa", "bb", "c", "ddd", "e"));

        assertEquals(3, batches.size());
        assertEquals(List.of(0, 1), batches.get(0).inputs().stream().map(
                InfinityEmbeddingBatcher.IndexedInput::globalIndex).toList());
        assertEquals(List.of(2, 3), batches.get(1).inputs().stream().map(
                InfinityEmbeddingBatcher.IndexedInput::globalIndex).toList());
        assertEquals(List.of(4), batches.get(2).inputs().stream().map(
                InfinityEmbeddingBatcher.IndexedInput::globalIndex).toList());
    }

    @Test
    void overlongSingleInputFailsWithoutTruncationOrHttpCall() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var result = fixture.adapter(2).embed(request(List.of("toolong")));

            assertEquals("EMBEDDING_INPUT_TOKEN_LIMIT_EXCEEDED", result.failure().errorCode());
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void adapterKeepsGlobalOrderAcrossMultipleValidatedBatches() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var result = fixture.adapter(2).embed(request(List.of("a", "b", "c")));

            assertEquals(2, fixture.requests.get());
            assertEquals(3, result.value().size());
            assertEquals(1.0f, result.value().get(0)[0]);
            assertEquals(1.0f, result.value().get(1)[1]);
            assertEquals(-1.0f, result.value().get(2)[0]);
            assertEquals(3, result.usage().inputTokens());
        }
    }

    @Test
    void partialSecondBatchFailsTheWholeInvocationWithoutExposingPartialVectors() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            var result = fixture.adapter(2).embed(request(List.of("a", "b", "c")));

            assertEquals(2, fixture.requests.get());
            assertEquals("EMBEDDING_COUNT_MISMATCH", result.failure().errorCode());
            assertEquals(null, result.value());
        }
    }

    @Test
    void identityMismatchFailsBeforeNetworkAndNeverSwitchesProvider() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            EmbeddingRequest mismatch = new EmbeddingRequest(
                    new ProviderIdentity("other", "embedding-model", "revision-1"),
                    NOW.plusSeconds(10), List.of("a"));

            var result = fixture.adapter(2).embed(mismatch);

            assertEquals("EMBEDDING_PROVIDER_MISMATCH", result.failure().errorCode());
            assertFalse(result.failure().retryable());
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void validatorRejectsCountOrderDimensionNonFiniteAndCosineZeroNorm() {
        InfinityEmbeddingConfiguration config = configuration(URI.create("http://127.0.0.1:1"), 2);
        InfinityVectorValidator validator = new InfinityVectorValidator();

        assertCode("EMBEDDING_COUNT_MISMATCH", () -> validator.validate(
                response(List.of(data(0, 1, 0))), 2, config));
        assertCode("EMBEDDING_ORDER_UNPROVEN", () -> validator.validate(
                response(List.of(data(1, 1, 0))), 1, config));
        assertCode("EMBEDDING_DIMENSION_MISMATCH", () -> validator.validate(
                response(List.of(data(0, 1))), 1, config));
        assertCode("EMBEDDING_NON_FINITE", () -> validator.validate(
                response(List.of(new InfinityEmbeddingDtos.EmbeddingData(0, List.of(Double.NaN, 0.0)))),
                1, config));
        assertCode("EMBEDDING_ZERO_NORM", () -> validator.validate(
                response(List.of(data(0, 0, 0))), 1,
                new InfinityEmbeddingConfiguration("infinity", URI.create("http://127.0.0.1:1"),
                        "embedding-model", "revision-1", 2, Normalization.NONE,
                        DistanceMetric.COSINE, 2, 2, 10)));
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(VectorContractException.class, action::run).getMessage());
    }

    private static InfinityEmbeddingDtos.EmbeddingData data(int index, double... values) {
        ArrayList<Double> vector = new ArrayList<>();
        for (double value : values) vector.add(value);
        return new InfinityEmbeddingDtos.EmbeddingData(index, vector);
    }

    private static InfinityEmbeddingDtos.EmbeddingResponse response(
            List<InfinityEmbeddingDtos.EmbeddingData> data) {
        return new InfinityEmbeddingDtos.EmbeddingResponse("embedding-model", data,
                new InfinityEmbeddingDtos.Usage(data.size(), data.size()));
    }

    private static EmbeddingRequest request(List<String> inputs) {
        return new EmbeddingRequest(new ProviderIdentity("infinity", "embedding-model", "revision-1"),
                NOW.plusSeconds(10), inputs);
    }

    private static InfinityEmbeddingConfiguration configuration(URI baseUrl, int maxTokens) {
        return new InfinityEmbeddingConfiguration("infinity", baseUrl, "embedding-model", "revision-1",
                2, Normalization.L2_UNIT, DistanceMetric.COSINE, 2, 2, maxTokens);
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger requests = new AtomicInteger();
        private final boolean partialSecondBatch;
        private final HttpServer server;

        private Fixture(boolean partialSecondBatch) throws Exception {
            this.partialSecondBatch = partialSecondBatch;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/embeddings", exchange -> {
                int requestNumber = requests.incrementAndGet();
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                List<String> inputs = new ArrayList<>();
                request.path("input").forEach(node -> inputs.add(node.textValue()));
                if (partialSecondBatch && requestNumber == 2) inputs.clear();
                var data = new ArrayList<java.util.Map<String, Object>>();
                for (int index = 0; index < inputs.size(); index++) {
                    data.add(java.util.Map.of("index", index, "embedding", vector(inputs.get(index))));
                }
                byte[] body = JSON.writeValueAsBytes(java.util.Map.of(
                        "model", "embedding-model", "data", data,
                        "usage", java.util.Map.of("prompt_tokens", inputs.size(), "total_tokens", inputs.size())));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private InfinityEmbeddingAdapter adapter(int maxTokens) {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            return new InfinityEmbeddingAdapter(configuration(base, maxTokens), String::length,
                    HttpClient.newHttpClient(), JSON, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private static List<Double> vector(String input) {
            return switch (input) {
                case "a" -> List.of(1.0, 0.0);
                case "b" -> List.of(0.0, 1.0);
                default -> List.of(-1.0, 0.0);
            };
        }

        @Override public void close() { server.stop(0); }
    }
}
