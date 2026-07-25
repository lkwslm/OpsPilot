package io.github.opspilot.sample.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class InventoryClient {
    private final RestClient client;
    private final String inventoryBaseUrl;

    InventoryClient(RestClient client, @Value("${sample.inventory-base-url}") String inventoryBaseUrl) {
        this.client = client;
        this.inventoryBaseUrl = inventoryBaseUrl;
    }

    void reserve(String sku, int quantity) {
        try {
            client.post().uri(inventoryBaseUrl + "/api/inventory/{sku}/reserve", sku)
                    .headers(headers -> headers.addAll(CorrelationFilter.correlationHeaders()))
                    .body(new ReserveRequest(quantity)).retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new InventoryFailure(response.getStatusCode().value());
                    }).toBodilessEntity();
        } catch (InventoryFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new InventoryFailure(503);
        }
    }

    record ReserveRequest(int quantity) { }

    static final class InventoryFailure extends RuntimeException {
        private final int status;
        InventoryFailure(int status) {
            super("INVENTORY_RESERVATION_FAILED");
            this.status = status;
        }
        int status() { return status; }
    }
}
