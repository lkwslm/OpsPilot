package io.github.opspilot.sample.inventory;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"fault-lab", "test"})
@RequestMapping("/internal/faults")
final class FaultController {
    private final FaultState faults;

    FaultController(FaultState faults) {
        this.faults = faults;
    }

    @PutMapping("/inventory")
    FaultState.Snapshot configure(@RequestBody FaultRequest request) {
        faults.configure(request.delayMillis(), request.exception(), request.block());
        return faults.snapshot();
    }

    @PostMapping("/reset")
    FaultState.Snapshot reset() {
        faults.reset();
        return faults.snapshot();
    }

    @ExceptionHandler(FaultState.ControlledFault.class)
    ResponseEntity<ErrorResponse> controlledFault() {
        return ResponseEntity.status(503).body(new ErrorResponse("CONTROLLED_INVENTORY_FAULT"));
    }

    record FaultRequest(Long delayMillis, Boolean exception, Boolean block) { }
    record ErrorResponse(String code) { }
}
