package dev.oncall.sample.order;

import dev.oncall.sample.order.Model.OrderNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

@RestControllerAdvice
class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(OrderNotFoundException e) {
        log.warn("Order not found: id={}", e.id);
        return body(HttpStatus.NOT_FOUND, "order not found");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<Map<String, String>> paymentUnavailable(ResourceAccessException e, HttpServletRequest req) {
        log.error("Payment request failed on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.GATEWAY_TIMEOUT, "payment unavailable");
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<Map<String, String>> databaseUnavailable(DataAccessResourceFailureException e,
                                                            HttpServletRequest req) {
        log.error("Database access failed on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "database unavailable");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
