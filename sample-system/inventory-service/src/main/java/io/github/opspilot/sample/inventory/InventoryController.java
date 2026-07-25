package io.github.opspilot.sample.inventory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class InventoryController {
    private final InventoryApplicationService service;
    private final FaultState faults;

    InventoryController(InventoryApplicationService service, FaultState faults) {
        this.service = service;
        this.faults = faults;
    }

    @GetMapping("/api/inventory/{sku}")
    ResponseEntity<?> get(@PathVariable("sku") String sku) {
        faults.beforeRequest();
        try {
            return ResponseEntity.ok(service.get(sku));
        } catch (InventoryApplicationService.InventoryNotFound failure) {
            return ResponseEntity.status(404).body(new ErrorResponse("INVENTORY_NOT_FOUND"));
        }
    }

    @PostMapping("/api/inventory/{sku}/reserve")
    ResponseEntity<?> reserve(@PathVariable("sku") String sku, @RequestBody ReserveRequest request) {
        faults.beforeRequest();
        if (!sku.matches("[A-Za-z0-9._-]{1,64}") || request.quantity() < 1) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_RESERVATION"));
        }
        try {
            return ResponseEntity.ok(service.reserve(sku, request.quantity()));
        } catch (InventoryApplicationService.InventoryNotFound failure) {
            return ResponseEntity.status(404).body(new ErrorResponse("INVENTORY_NOT_FOUND"));
        } catch (InventoryApplicationService.InventoryConflict failure) {
            return ResponseEntity.status(409).body(new ErrorResponse("INVENTORY_VERSION_CONFLICT"));
        }
    }

    record ReserveRequest(int quantity) { }
    record ErrorResponse(String code) { }
}
