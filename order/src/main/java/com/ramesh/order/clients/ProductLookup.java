package com.ramesh.order.clients;

import com.ramesh.order.dtos.ProductResponse;
import com.ramesh.order.exceptions.ProductNotFoundException;
import com.ramesh.order.exceptions.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

//The one place the product-service breaker wraps.
//
//Why this class exists at all: @CircuitBreaker is applied by a Spring AOP proxy, and a
//proxy can only intercept calls that arrive from outside the bean. When CartService
//called its own helper method, the call went straight down `this` and never touched the
//proxy - so an annotation there would have done nothing, whatever its visibility.
//Moving the remote call into a separate bean makes CartService -> ProductLookup a real
//cross-bean call, which the proxy does intercept.
//
//It also keeps the breaker's scope honest: only the HTTP call is inside it. If the
//annotation sat on CartService.addToCart, the JPA work and the stock check would count
//towards a breaker named after product-service.
@Component
@RequiredArgsConstructor
public class ProductLookup {

    private static final Logger logger = LoggerFactory.getLogger(ProductLookup.class);

    private final ProductServiceClient productServiceClient;

    //"product-service" must match the instances: key in order-service.yml. A name with no
    //matching instance is not an error - resilience4j creates the breaker from the
    //"default" config instead, which is easy to do by typo and hard to notice.
    @CircuitBreaker(name = "product-service", fallbackMethod = "getProductFallback")
    public ProductResponse getProduct(Long productId) {
        ResponseEntity<ProductResponse> response = productServiceClient.getProductById(productId);
        return response == null ? null : response.getBody();
    }

    //Two fallbacks, same name, different last parameter. resilience4j walks the thrown
    //exception's superclass chain and picks the most specific match, so this pair replaces
    //the instanceof check the old CircuitBreakerFactory lambda needed.
    //Private is fine: the fallback is found with ReflectionUtils.doWithMethods and made
    //accessible, not looked up as a public method.

    //404 is a real answer from a healthy service, so keep the existing 404 semantics
    //rather than reporting an outage. ignore-exceptions in the YAML keeps it out of the
    //failure rate, but it still propagates here - ignoring only affects the statistics.
    private ProductResponse getProductFallback(Long productId, HttpClientErrorException.NotFound ex) {
        throw new ProductNotFoundException("Product with id " + productId + " does not exist");
    }

    //Everything else: transport failure, a slow call, or CallNotPermittedException once
    //the breaker is OPEN. GlobalExceptionHandler turns this into 503.
    private ProductResponse getProductFallback(Long productId, Throwable throwable) {
        logger.warn("product-service call failed for productId={}: {}", productId, throwable.toString());
        throw new ServiceUnavailableException("product-service", throwable);
    }
}
