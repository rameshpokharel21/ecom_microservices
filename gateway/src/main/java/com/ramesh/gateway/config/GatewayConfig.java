package com.ramesh.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

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

    //replenishRate=1/s, burstCapacity=1, requestedTokens=1: one request per second per
    //key, no burst allowance. Deliberately strict so JMeter shows 429s immediately.
    //
    //This bean shadows the one in GatewayRedisAutoConfiguration, which is
    //@ConditionalOnMissingBean, so there is no clash. The three-arg constructor leaves
    //redisTemplate and script null: RedisRateLimiter is ApplicationContextAware and
    //fills them in setApplicationContext() by pulling ReactiveStringRedisTemplate and
    //the "redisRequestRateLimiterScript" bean out of the context. Both still come from
    //the auto-configuration, so spring-boot-starter-data-redis-reactive must stay on
    //the classpath and spring.data.redis.host must point at redis-server - see
    //cloud-gateway.yml. A bad host does not fail startup and does not fail requests:
    //the limiter fails OPEN and every request is allowed.
    @Bean
    public RedisRateLimiter redisRateLimiter(){
        return new RedisRateLimiter(10, 20, 1);
    }

    //The key is the Redis bucket id: request_rate_limiter.{key}.tokens. One bucket per
    //client IP here.
    //
    //getAddress().getHostAddress(), NOT getHostName(): getHostName() on an unresolved
    //InetSocketAddress performs a REVERSE DNS lookup, which blocks. On the gateway that
    //runs on a reactor-http-epoll thread, so under JMeter load every request would stall
    //the event loop waiting on PTR records that a Docker bridge address does not have.
    //getHostAddress() is a pure string format of the bytes - no I/O.
    //
    //Note what this actually buckets by in Docker: requests from the host all arrive
    //through the bridge, so they share one source address and therefore one bucket.
    //That is fine for a load test (the whole run is limited to 1 req/s) but it is not
    //per-user limiting - for that, key on a header or the authenticated principal.
    //
    //Returning a key instead of Mono.empty() on an unknown address is deliberate:
    //RequestRateLimiterGatewayFilterFactory defaults denyEmptyKey=true, so an empty key
    //is answered with 403 FORBIDDEN rather than being rate limited.
    @Bean
    public KeyResolver hostNameKeyResolver(){
        return exchange -> {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just(remote == null || remote.getAddress() == null
                    ? "unknown"
                    : remote.getAddress().getHostAddress());
        };
    }
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder){
        return builder.routes()
                //Rate limiting is per route: declaring the RedisRateLimiter and
                //KeyResolver beans only makes them available, it attaches them to
                //nothing. A route without .requestRateLimiter(...) forwards everything,
                //which is why /api/products/** was unlimited while /api/users/** was not.
                //
                //requestRateLimiter is declared BEFORE circuitBreaker, and that order is
                //the execution order: GatewayFilterSpec.filter() gives both filters
                //order 0 (neither implements Ordered), and FilteringWebHandler sorts with
                //AnnotationAwareOrderComparator, a stable sort, so insertion order wins.
                //Rate limiter first means a throttled request returns 429 without
                //entering cb.run(...). Reversed, the 429 completes the Mono normally and
                //resilience4j records it as a SUCCESS, padding the sliding window with
                //calls that never reached product-service.
                //
                //The buckets do not collide across routes: the filter calls
                //isAllowed(route.getId(), key), so this one is
                //request_rate_limiter.{PRODUCT-SERVICE.<ip>} while the user route uses
                //request_rate_limiter.{USER-SERVICE.<ip>} - one shared bean, separate keys.
                .route("PRODUCT-SERVICE", r -> r
                        .path("/api/products/**")
                        .filters(f -> f
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(hostNameKeyResolver())
                                )
                                .circuitBreaker(config -> config
                                        .setName(PRODUCT_CB)
                                        .setFallbackUri("forward:/fallback/products")
                                ))
                        .uri("lb://product-service")
                )
                .route("USER-SERVICE", r -> r
                        .path("/api/users/**")
                        .filters(f -> f
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(redisRateLimiter())
                                        .setKeyResolver(hostNameKeyResolver())
                                )
                                .circuitBreaker(config -> config
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
