package com.ramesh.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;

//Circuit breaker configuration for the two downstream services CartService calls.
//
//This customizes the auto-configured Resilience4JCircuitBreakerFactory instead of
//declaring a CircuitBreakerRegistry bean. That distinction matters: the registry bean
//in resilience4j's auto-config is @ConditionalOnMissingBean and is assembled from the
//configuration properties, an EventConsumerRegistry and a CompositeCustomizer.
//Publishing our own registry would replace all three, silently disabling
///actuator/circuitbreakerevents and any property-based config. A Customizer leaves the
//auto-configured registry in place and only adds to it.
@Configuration
public class CustomCircuitBreakerConfig {

    //Breaker ids. These are arbitrary labels, not Eureka service ids - they only have to
    //match the string passed to circuitBreakerFactory.create(...) in CartService.
    public static final String PRODUCT_SERVICE_CB = "product-service";
    public static final String USER_SERVICE_CB = "user-service";

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> downstreamServicesCustomizer() {
        return factory -> factory.configure(builder -> builder
                        .circuitBreakerConfig(CircuitBreakerConfig.custom()
                                //count-based: with low traffic a time-based window is
                                //usually empty, so rates never get computed
                                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(10)
                                //resilience4j's default is 100, which means the breaker
                                //would never open in a demo. 5 makes it observable.
                                .minimumNumberOfCalls(5)
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .waitDurationInOpenState(Duration.ofSeconds(10))
                                .failureRateThreshold(50)
                                //without this, OPEN -> HALF_OPEN only happens when a call
                                //arrives after the wait duration; with it a scheduler does
                                //the transition, so it is visible in actuator without traffic
                                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                                //a 404 means "no such product/user", not "the service is
                                //broken" - it must not push the breaker towards OPEN.
                                //Ignored exceptions still propagate, so the fallbacks in
                                //CartService translate them into the usual 404 responses.
                                .ignoreExceptions(HttpClientErrorException.NotFound.class)
                                .build())
                        //Spring Cloud wraps every run() in a TimeLimiter whose default is
                        //1 second - short enough that a cold JIT or first Hibernate hit
                        //trips it. Set it explicitly rather than inheriting that default.
                        .timeLimiterConfig(TimeLimiterConfig.custom()
                                .timeoutDuration(Duration.ofSeconds(4))
                                .build()),
                PRODUCT_SERVICE_CB, USER_SERVICE_CB);
    }
}
