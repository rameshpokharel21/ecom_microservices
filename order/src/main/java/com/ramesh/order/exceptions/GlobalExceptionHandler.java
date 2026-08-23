package com.ramesh.order.exceptions;

import com.ramesh.order.dtos.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //CartService.addToCart parses productId with Long.valueOf because "a malformed id is
    //a bad request, not a product-service failure". Without this handler that intent was
    //not true over HTTP: the NumberFormatException fell through to Spring's default and
    //came back as a 500, which tells a client to retry a request that can never succeed.
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<ErrorResponse> handleMalformedProductId(NumberFormatException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PRODUCT_ID", "productId must be numeric"));
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
