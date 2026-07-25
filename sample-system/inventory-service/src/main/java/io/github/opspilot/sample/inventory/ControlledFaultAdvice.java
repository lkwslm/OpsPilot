package io.github.opspilot.sample.inventory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ControlledFaultAdvice {
    @ExceptionHandler(FaultState.ControlledFault.class)
    ResponseEntity<ErrorResponse> controlledFault() {
        return ResponseEntity.status(503).body(new ErrorResponse("CONTROLLED_INVENTORY_FAULT"));
    }

    record ErrorResponse(String code) { }
}
