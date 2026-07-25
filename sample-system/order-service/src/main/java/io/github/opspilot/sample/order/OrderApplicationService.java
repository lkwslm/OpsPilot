package io.github.opspilot.sample.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class OrderApplicationService {
    private final OrderRepository orders;
    private final InventoryClient inventory;

    OrderApplicationService(OrderRepository orders, InventoryClient inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    @Transactional
    OrderRepository.Order create(String sku, int quantity) {
        inventory.reserve(sku, quantity);
        return orders.create(sku, quantity);
    }

    OrderRepository.Order get(UUID id) {
        return orders.find(id).orElseThrow(OrderNotFound::new);
    }

    static final class OrderNotFound extends RuntimeException { }
}
