package io.github.opspilot.sample.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GatewayControllerTest {
    @Test
    void gatewayForwardsFrozenApiAndCorrelationWithoutOwningState() throws Exception {
        AtomicReference<String> requestId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders", exchange -> {
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = "{\"status\":\"RESERVED\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var ignoredRequest = MDC.putCloseable("requestId", "request-1");
             var ignoredTrace = MDC.putCloseable("traceId", "trace-1");
             var ignoredRun = MDC.putCloseable("runId", "run-1")) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GatewayController controller = new GatewayController(RestClient.create(), base, base);
            var response = controller.createOrder("{\"sku\":\"SKU-001\",\"quantity\":1}");
            assertEquals(201, response.getStatusCode().value());
            assertEquals("request-1", requestId.get());
            assertFalse(GatewayController.class.getDeclaredFields()[0].getType().getName().contains("Repository"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downstreamTransportFailureMapsToStableUnavailableBoundary() {
        try (var ignoredRequest = MDC.putCloseable("requestId", "request-1");
             var ignoredTrace = MDC.putCloseable("traceId", "trace-1")) {
            GatewayController controller = new GatewayController(
                    RestClient.create(), "http://127.0.0.1:1", "http://127.0.0.1:1");
            var response = controller.order("00000000-0000-4000-8000-000000000001");
            assertEquals(503, response.getStatusCode().value());
            assertEquals("{\"code\":\"DOWNSTREAM_UNAVAILABLE\"}", response.getBody());
        }
    }
}
