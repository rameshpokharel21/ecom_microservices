package com.ramesh.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rewrites the X-User-ID header from the validated JWT, so downstream services learn
 * who the caller is from Keycloak rather than from the caller.
 *
 * <p>SecurityConfig authenticates the request but changes nothing about its content.
 * order-service reads {@code @RequestHeader("X-User-ID")} in CartController and
 * OrderController and trusts it - it has no security on the classpath and no way to
 * tell a gateway-supplied header from a client-supplied one. So before this filter
 * existed, holding any valid token was enough to operate on any other user's cart.
 * Authentication alone did not close that; this does.
 *
 * <p><b>The order of the two header operations is the whole point.</b> The inbound
 * value is removed FIRST and only then set from the token. Setting without removing
 * would be enough for the happy path and would still leave the hole open on any path
 * where no token is present - a permitted route, say - because the client's own header
 * would survive untouched. Remove-then-set means X-User-ID downstream is either what
 * this filter wrote or absent; it can never be what the caller sent.
 *
 * <p>A GlobalFilter rather than per-route {@code .filters(...)} entries in
 * GatewayConfig: this has to hold for every route, including ones added later, and a
 * rule that must never be forgotten should not be one that has to be remembered at
 * each call site.
 *
 * <p>The identifier is the {@code sub} claim - Keycloak's stable, immutable user id.
 * Not {@code preferred_username}, which a user can change, and not {@code email},
 * which can be reassigned. The consequence is that user-service records must be keyed
 * by that same UUID, which is why user-service sets the Mongo _id from it at signup
 * instead of letting Mongo generate one.
 */
@Component
public class UserIdRelayFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(UserIdRelayFilter.class);

    //Must match the @RequestHeader name in order-service's CartController and
    //OrderController. HTTP header names are case-insensitive, so the casing here is
    //cosmetic, but the spelling is not.
    private static final String USER_ID_HEADER = "X-User-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //getPrincipal() reads the SecurityContext because Spring Security wraps the
        //exchange (SecurityContextServerWebExchange). On a permitted path such as
        //actuator or the Eureka dashboard there is no Authentication at all, so this
        //Mono completes EMPTY - which is why defaultIfEmpty("") is here rather than as
        //defensive padding. Without it flatMap would never run on those paths and the
        //header would pass through unstripped, i.e. the empty case is exactly the case
        //that has to be handled.
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> auth.getToken().getSubject())
                .defaultIfEmpty("")
                .flatMap(subject -> chain.filter(withUserId(exchange, subject)));
    }

    private ServerWebExchange withUserId(ServerWebExchange exchange, String subject) {
        String forged = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
        if (forged != null) {
            //Worth a log line: after this filter ships, a client still sending the
            //header is either an old script or someone probing. Either way the value is
            //discarded, so this is informational rather than a rejection - failing the
            //request instead would break every existing collection and curl snippet.
            logger.debug("Discarding client-supplied {}: {}", USER_ID_HEADER, forged);
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    if (!subject.isEmpty()) {
                        headers.set(USER_ID_HEADER, subject);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * Anything below NettyRoutingFilter (LOWEST_PRECEDENCE) would do, since that is
     * what finally copies the headers onto the outbound request, but -1 keeps this in
     * front of RouteToRequestUrlFilter (10000) too, so the header is settled before any
     * routing decision can observe it.
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
