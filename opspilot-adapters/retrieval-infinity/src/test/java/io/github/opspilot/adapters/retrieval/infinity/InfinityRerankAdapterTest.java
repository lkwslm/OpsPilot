package io.github.opspilot.adapters.retrieval.infinity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.RerankPort.DocumentCandidate;
import io.github.opspilot.core.port.provider.RerankPort.RerankRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfinityRerankAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final ProviderIdentity IDENTITY =
            new ProviderIdentity("infinity", "rerank-model", "revision-1");
    private static final List<DocumentCandidate> CANDIDATES = List.of(
            new DocumentCandidate("doc-a", "甲"),
            new DocumentCandidate("doc-b", "乙"),
            new DocumentCandidate("doc-c", "丙"));

    @Test
    void roundTripKeepsStableIdsOriginalIndicesRawScoresRanksAndLockedIdentity() throws Exception {
        try (Fixture fixture = Fixture.json("rerank-model", List.of(
                result(2, -2.5), result(0, 7.25), result(1, 1.5)))) {
            var response = fixture.adapter("rerank-model", "revision-1", fixedClock())
                    .rerank(request(IDENTITY, NOW.plusSeconds(5), CANDIDATES));

            assertNull(response.failure());
            assertEquals(List.of("doc-a", "doc-b", "doc-c"),
                    response.value().stream().map(document -> document.documentId()).toList());
            assertEquals(List.of(0, 1, 2),
                    response.value().stream().map(document -> document.originalIndex()).toList());
            assertEquals(List.of(7.25, 1.5, -2.5),
                    response.value().stream().map(document -> document.score()).toList());
            assertEquals(List.of(1, 2, 3),
                    response.value().stream().map(document -> document.rank()).toList());
            response.value().forEach(document -> assertEquals(IDENTITY, document.identity()));
            List<String> sentDocuments = new java.util.ArrayList<>();
            fixture.lastRequest.get().path("documents").forEach(
                    document -> sentDocuments.add(document.textValue()));
            assertEquals(List.of("甲", "乙", "丙"), sentDocuments);
            assertEquals(3, fixture.lastRequest.get().path("top_n").intValue());
            assertFalse(fixture.lastRequest.get().path("return_documents").booleanValue());
            assertFalse(EmbeddingPort.class.isAssignableFrom(InfinityRerankAdapter.class));
        }
    }

    @Test
    void identityAndDuplicateDocumentIdsFailBeforeNetwork() throws Exception {
        try (Fixture fixture = Fixture.json("rerank-model", List.of(
                result(0, 1), result(1, 0), result(2, -1)))) {
            var adapter = fixture.adapter("rerank-model", "revision-1", fixedClock());
            var mismatch = adapter.rerank(request(
                    new ProviderIdentity("infinity", "rerank-model", "other"),
                    NOW.plusSeconds(5), CANDIDATES));
            var duplicates = adapter.rerank(request(IDENTITY, NOW.plusSeconds(5), List.of(
                    new DocumentCandidate("same", "甲"), new DocumentCandidate("same", "乙"))));

            assertEquals("RERANK_REVISION_MISMATCH", mismatch.failure().errorCode());
            assertEquals("RERANK_DUPLICATE_DOCUMENT_ID", duplicates.failure().errorCode());
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void validatorRejectsPartialDuplicateOutOfRangeNonFiniteAndModelMismatch() {
        InfinityRerankValidator validator = new InfinityRerankValidator();
        InfinityRerankConfiguration configuration = configuration(
                URI.create("http://127.0.0.1:1/rerank"), "rerank-model", "revision-1");

        assertFailure("RERANK_RESULT_COUNT_MISMATCH", () -> validator.validate(
                response("rerank-model", List.of(result(0, 1))), CANDIDATES, configuration));
        assertFailure("RERANK_DUPLICATE_INDEX", () -> validator.validate(
                response("rerank-model", List.of(result(0, 1), result(0, 0), result(2, -1))),
                CANDIDATES, configuration));
        assertFailure("RERANK_INDEX_OUT_OF_RANGE", () -> validator.validate(
                response("rerank-model", List.of(result(0, 1), result(1, 0), result(3, -1))),
                CANDIDATES, configuration));
        assertFailure("RERANK_SCORE_NON_FINITE", () -> validator.validate(
                response("rerank-model", List.of(result(0, 1), result(1, Double.NaN), result(2, -1))),
                CANDIDATES, configuration));
        assertFailure("RERANK_MODEL_MISMATCH", () -> validator.validate(
                response("other", List.of(result(0, 1), result(1, 0), result(2, -1))),
                CANDIDATES, configuration));
    }

    @Test
    void httpErrorMappingIsRetryableOnlyForRateLimitAndServerFailuresAndNeverLeaksBody() throws Exception {
        assertHttpFailure(429, true);
        assertHttpFailure(503, true);
        assertHttpFailure(400, false);
    }

    @Test
    void malformedBodyFailsClosedWithoutReturningOriginalOrder() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> send(exchange, 200, "{partial"))) {
            var response = fixture.adapter("rerank-model", "revision-1", Clock.systemUTC())
                    .rerank(request(IDENTITY, Instant.now().plusSeconds(2), CANDIDATES));

            assertEquals("RERANK_RESPONSE_MALFORMED", response.failure().errorCode());
            assertFalse(response.failure().retryable());
            assertNull(response.value());
        }
    }

    @Test
    void timeoutDiscardsPartialResponse() throws Exception {
        try (Fixture fixture = new Fixture(exchange -> {
            sleep(300);
            sendJson(exchange, "rerank-model", List.of(result(0, 1)));
        })) {
            var response = fixture.adapter("rerank-model", "revision-1", Clock.systemUTC())
                    .rerank(request(IDENTITY, Instant.now().plusMillis(50), CANDIDATES));

            assertEquals("RERANK_TIMEOUT", response.failure().errorCode());
            assertNull(response.value());
        }
    }

    @Test
    void interruptionCancelsInFlightCallAndReturnsNoCandidates() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(exchange -> {
            entered.countDown();
            sleep(500);
            sendJson(exchange, "rerank-model", List.of(
                    result(0, 1), result(1, 0), result(2, -1)));
        })) {
            AtomicReference<io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult<
                    List<io.github.opspilot.core.port.provider.RerankPort.RankedDocument>>> outcome =
                    new AtomicReference<>();
            Thread caller = Thread.ofPlatform().start(() -> outcome.set(
                    fixture.adapter("rerank-model", "revision-1", Clock.systemUTC()).rerank(
                            request(IDENTITY, Instant.now().plusSeconds(5), CANDIDATES))));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);

            assertEquals("RERANK_CANCELLED", outcome.get().failure().errorCode());
            assertNull(outcome.get().value());
        }
    }

    @Test
    void twoModelsRemainIsolatedUnderConcurrentCalls() throws Exception {
        try (Fixture first = Fixture.json("model-a", List.of(result(0, 1)));
             Fixture second = Fixture.json("model-b", List.of(result(0, 2)))) {
            var candidate = List.of(new DocumentCandidate("doc", "内容"));
            AtomicReference<String> firstModel = new AtomicReference<>();
            AtomicReference<String> secondModel = new AtomicReference<>();
            Thread a = Thread.ofPlatform().start(() -> firstModel.set(first.adapter(
                    "model-a", "rev-a", Clock.systemUTC()).rerank(request(
                    new ProviderIdentity("infinity", "model-a", "rev-a"),
                    Instant.now().plusSeconds(2), candidate)).value().getFirst().identity().modelId()));
            Thread b = Thread.ofPlatform().start(() -> secondModel.set(second.adapter(
                    "model-b", "rev-b", Clock.systemUTC()).rerank(request(
                    new ProviderIdentity("infinity", "model-b", "rev-b"),
                    Instant.now().plusSeconds(2), candidate)).value().getFirst().identity().modelId()));
            a.join();
            b.join();

            assertEquals("model-a", firstModel.get());
            assertEquals("model-b", secondModel.get());
            assertEquals("model-a", first.lastRequest.get().path("model").textValue());
            assertEquals("model-b", second.lastRequest.get().path("model").textValue());
        }
    }

    private static void assertHttpFailure(int status, boolean retryable) throws Exception {
        try (Fixture fixture = new Fixture(exchange -> send(exchange, status,
                "credential=must-not-leak"))) {
            var response = fixture.adapter("rerank-model", "revision-1", Clock.systemUTC())
                    .rerank(request(IDENTITY, Instant.now().plusSeconds(2), CANDIDATES));
            assertEquals("INFINITY_RERANK_HTTP_" + status, response.failure().errorCode());
            assertEquals(retryable, response.failure().retryable());
            assertEquals("Infinity rerank invocation failed", response.failure().redactedSummary());
        }
    }

    private static void assertFailure(String expected, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException failure) {
            assertEquals(expected, failure.getMessage());
            return;
        }
        throw new AssertionError("expected " + expected);
    }

    private static RerankRequest request(
            ProviderIdentity identity, Instant deadline, List<DocumentCandidate> candidates) {
        return new RerankRequest(identity, deadline, "查询", candidates);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static InfinityRerankConfiguration configuration(
            URI uri, String model, String revision) {
        return new InfinityRerankConfiguration(uri, "infinity", model, revision);
    }

    private static InfinityRerankDtos.Result result(int index, double score) {
        return new InfinityRerankDtos.Result(index, score);
    }

    private static InfinityRerankDtos.Response response(
            String model, List<InfinityRerankDtos.Result> results) {
        return new InfinityRerankDtos.Response(model, results,
                new InfinityRerankDtos.Usage(results.size(), results.size()));
    }

    private static void sendJson(
            HttpExchange exchange, String model, List<InfinityRerankDtos.Result> results) throws IOException {
        send(exchange, 200, JSON.writeValueAsString(Map.of(
                "model", model,
                "results", results.stream().map(item -> Map.of(
                        "index", item.index(), "relevance_score", item.relevanceScore())).toList(),
                "usage", Map.of("prompt_tokens", results.size(), "total_tokens", results.size()))));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();

        private Fixture(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rerank", exchange -> {
                requests.incrementAndGet();
                lastRequest.set(JSON.readTree(exchange.getRequestBody()));
                responder.respond(exchange);
            });
            server.start();
        }

        private static Fixture json(
                String model, List<InfinityRerankDtos.Result> results) throws IOException {
            return new Fixture(exchange -> sendJson(exchange, model, results));
        }

        private InfinityRerankAdapter adapter(String model, String revision, Clock clock) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            return new InfinityRerankAdapter(configuration(uri, model, revision),
                    HttpClient.newHttpClient(), JSON, clock);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
