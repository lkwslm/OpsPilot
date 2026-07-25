package io.github.opspilot.sample.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
final class OrderController {
    private final OrderApplicationService service;

    OrderController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/orders")
    ResponseEntity<?> create(@RequestBody CreateOrderRequest request) {
        if (request.sku() == null || !request.sku().matches("[A-Za-z0-9._-]{1,64}") || request.quantity() < 1) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ORDER"));
        }
        try {
            return ResponseEntity.status(201).body(service.create(request.sku(), request.quantity()));
        } catch (InventoryClient.InventoryFailure failure) {
            int status = failure.status() == 409 ? 409 : 503;
            return ResponseEntity.status(status).body(new ErrorResponse(
                    status == 409 ? "INVENTORY_CONFLICT" : "INVENTORY_UNAVAILABLE"));
        }
    }

    @GetMapping("/api/orders/{id}")
    ResponseEntity<?> get(@PathVariable("id") UUID id) {
        try {
            return ResponseEntity.ok(service.get(id));
        } catch (OrderApplicationService.OrderNotFound failure) {
            return ResponseEntity.status(404).body(new ErrorResponse("ORDER_NOT_FOUND"));
        }
    }

    record CreateOrderRequest(String sku, int quantity) { }
    record ErrorResponse(String code) { }
}
