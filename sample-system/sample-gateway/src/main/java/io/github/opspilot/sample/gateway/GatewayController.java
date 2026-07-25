package io.github.opspilot.sample.gateway;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@RestController
final class GatewayController {
    private final RestClient client;
    private final String orderBaseUrl;
    private final String inventoryBaseUrl;

    GatewayController(RestClient client,
                      @Value("${sample.order-base-url}") String orderBaseUrl,
                      @Value("${sample.inventory-base-url}") String inventoryBaseUrl) {
        this.client = client;
        this.orderBaseUrl = orderBaseUrl;
        this.inventoryBaseUrl = inventoryBaseUrl;
    }

    @PostMapping("/api/orders")
    ResponseEntity<String> createOrder(@RequestBody String body) {
        return forward("POST", orderBaseUrl + "/api/orders", body);
    }

    @GetMapping("/api/orders/{id}")
    ResponseEntity<String> order(@PathVariable("id") String id) {
        return forward("GET", orderBaseUrl + "/api/orders/" + id, null);
    }

    @GetMapping("/api/inventory/{sku}")
    ResponseEntity<String> inventory(@PathVariable("sku") String sku) {
        return forward("GET", inventoryBaseUrl + "/api/inventory/" + sku, null);
    }

    @PostMapping("/api/inventory/{sku}/reserve")
    ResponseEntity<String> reserve(@PathVariable("sku") String sku, @RequestBody String body) {
        return forward("POST", inventoryBaseUrl + "/api/inventory/" + sku + "/reserve", body);
    }

    private ResponseEntity<String> forward(String method, String uri, String body) {
        try {
            RestClient.RequestBodySpec request = client.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(uri).header(CorrelationFilter.REQUEST_ID, MDC.get("requestId"))
                    .header(CorrelationFilter.TRACE_ID, MDC.get("traceId"));
            String runId = MDC.get("runId");
            if (runId != null && !runId.isBlank()) request.header(CorrelationFilter.RUN_ID, runId);
            if (body != null) request.body(body).header(HttpHeaders.CONTENT_TYPE, "application/json");
            return request.retrieve().toEntity(String.class);
        } catch (RestClientResponseException failure) {
            return ResponseEntity.status(failure.getStatusCode()).body(failure.getResponseBodyAsString());
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).body("{\"code\":\"DOWNSTREAM_UNAVAILABLE\"}");
        }
    }
}
