package com.ramesh.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//Uses Fluent Java Routes API
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder){
        return builder.routes()
                .route("PRODUCT-SERVICE", r -> r
                        .path("/api/products/**")
                        .uri("lb://product-service")
                )
                .route("USER-SERVICE", r -> r
                        .path("/api/users/**")
                        .uri("lb://user-service")
                )
                .route("ORDER-SERVICE", r -> r
                        .path("/api/carts/**", "/api/orders/**")
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
