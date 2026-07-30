package io.github.opspilot.sample.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class OrderFailureHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OrderFailureHandler.class);

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ErrorResponse> databaseConnectionTimeout(RuntimeException failure) {
        LOG.error("error.code=DB_CONNECTION_TIMEOUT component=HikariCP", failure);
        return ResponseEntity.status(503).body(new ErrorResponse("DB_CONNECTION_TIMEOUT"));
    }

    record ErrorResponse(String code) { }
}
