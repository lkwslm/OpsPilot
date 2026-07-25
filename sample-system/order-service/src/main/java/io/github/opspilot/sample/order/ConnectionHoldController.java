package io.github.opspilot.sample.order;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"fault-lab", "test"})
@RequestMapping("/internal/faults/db")
final class ConnectionHoldController {
    private final ConnectionHoldManager holds;

    ConnectionHoldController(ConnectionHoldManager holds) {
        this.holds = holds;
    }

    @PutMapping("/holds/{slot}")
    Status hold(@PathVariable("slot") int slot) {
        holds.hold(slot);
        return new Status(holds.activeHolds());
    }

    @PostMapping("/reset")
    Status reset() {
        holds.reset();
        return new Status(holds.activeHolds());
    }

    record Status(int activeHolds) { }
}
