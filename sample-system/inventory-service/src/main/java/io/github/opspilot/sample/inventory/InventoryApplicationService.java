package io.github.opspilot.sample.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryApplicationService {
    private final InventoryRepository inventory;

    InventoryApplicationService(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    InventoryRepository.Inventory get(String sku) {
        return inventory.find(sku).orElseThrow(InventoryNotFound::new);
    }

    @Transactional
    InventoryRepository.Inventory reserve(String sku, int quantity) {
        InventoryRepository.Inventory current = get(sku);
        if (current.availableQuantity() < quantity || !inventory.reserve(sku, quantity, current.version())) {
            throw new InventoryConflict();
        }
        return get(sku);
    }

    static final class InventoryNotFound extends RuntimeException { }
    static final class InventoryConflict extends RuntimeException { }
}
