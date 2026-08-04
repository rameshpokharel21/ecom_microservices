package com.ramesh.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//Uses Fluent Java Routes API
@Configuration
public class GatewayConfig {

    //Each downstream service gets its own breaker. A single shared name would make
    //product-service failures open the breaker for /api/users/** and /api/orders/**
    //too, because Config.getId() returns the name whenever one is set and the filter
    //factory looks the breaker up in the shared registry by that id.
    //
    //These strings must match the resilience4j.circuitbreaker.instances keys in
    //cloud-gateway.yml. An unmatched name is not a startup error - resilience4j falls
    //back to configs.default - so a typo shows up only as wrong settings at runtime.
    private final static String PRODUCT_CB = "productServiceCB";
    private final static String USER_CB = "userServiceCB";
    private final static String ORDER_CB = "orderServiceCB";

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder){
        return builder.routes()
                .route("PRODUCT-SERVICE", r -> r
                        .path("/api/products/**")
                        .filters(f ->
                                f.circuitBreaker(config -> config
                                        .setName(PRODUCT_CB)
                                        .setFallbackUri("forward:/fallback/products")
                                ))
                        .uri("lb://product-service")
                )
                .route("USER-SERVICE", r -> r
                        .path("/api/users/**")
                        .filters(f ->
                                f.circuitBreaker(config -> config
                                        .setName(USER_CB)
                                        .setFallbackUri("forward:/fallback/users")

                                ))
                        .uri("lb://user-service")
                )
                .route("ORDER-SERVICE", r -> r
                        .path("/api/carts/**", "/api/orders/**")
                        .filters(f -> f
                                .circuitBreaker(config ->
                                        config.setName(ORDER_CB)
                                                .setFallbackUri("forward:/fallback/orders")
                                )
                        )
                        .uri("lb://order-service")
                )
                //for actuator on order service
                //uri will be http://localhost:8080/actuator/order/circuitbreakers
                //because 9090 is docker actuator
                //commented and instead used compose order-service ports: "9083:9090"
//                .route("ORDER-ACTUATOR", r -> r
//                        .path("/actuator/order/**")
//                        .filters(f ->
//                                f.rewritePath("/actuator/order/(?<seg>.*)",
//                                        "/actuator/${seg}")
//                                )
//                        .uri("http://order-service:9090")
//                )


                //dashboard at /eureka (not /eureka/main) so the browser resolves
                //Eureka's relative asset URLs (eureka/css/wro.css) to /eureka/css/...,
                //which the passthrough route below serves. Declared first: /eureka/**
                //also matches /eureka, and first match wins.
                .route("EUREKA-SERVER", r -> r
                        .path("/eureka")
                        .filters(f -> f.rewritePath("/eureka", "/"))
                        .uri("http://eureka-server:8761")


                )
                .route("EUREKA-SERVER-STATIC", r -> r
                        .path("/eureka/**")
                        .uri("http://eureka-server:8761")


                )
                .build();
    }
}
