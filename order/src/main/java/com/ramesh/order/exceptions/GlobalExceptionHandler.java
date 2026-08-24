package com.ramesh.order.exceptions;

import com.ramesh.order.dtos.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //Replaces the old NumberFormatException handler. Now that productId is a Long from
    //the DTO inward, a malformed id fails at the framework boundary instead of inside
    //CartService: Jackson raises HttpMessageNotReadableException on the body, and path
    //binding raises MethodArgumentTypeMismatchException on /carts/items/{productId}.
    //Both already default to 400 - this only keeps the ErrorResponse shape the other
    //handlers use, so a client never has to parse two different error formats.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "A field or path variable has the wrong type"));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PRODUCT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("USER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INSUFFICIENT_STOCK", ex.getMessage()));
    }

    // Raised by the circuit breaker fallbacks in CartService. Covers all three outage
    // shapes in one place: breaker OPEN (CallNotPermittedException), TimeLimiter expiry
    // (TimeoutException) and transport failures - none of which reach the handlers below
    // once a fallback has translated them.
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DOWNSTREAM_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleDownstreamUnavailable(RestClientException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DOWNSTREAM_UNAVAILABLE", "A dependent service is unreachable"));
    }

    // Thrown by Spring Cloud LoadBalancer (BlockingLoadBalancerClient) when Eureka has
    // no registered instances for a service-id yet, e.g. right after that service restarts.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleNoInstancesAvailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("DOWNSTREAM_UNAVAILABLE", "A dependent service is unreachable"));
    }
}
