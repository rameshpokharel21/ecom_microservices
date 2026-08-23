package com.ramesh.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * The gateway is a Spring Security RESOURCE SERVER: it validates the bearer token on
 * the way in and forwards the request. It never redirects to a login page, so nothing
 * here deals with authorization codes, sessions or cookies - the browser talks to
 * Keycloak directly and only ever shows the gateway a finished JWT.
 *
 * Every type on this page has a servlet twin with a nearly identical name, and picking
 * the wrong one is the main way this class goes wrong. The gateway is WebFlux, so the
 * reactive half is the correct half throughout:
 *
 *   servlet (WRONG here)                          reactive (correct)
 *   ------------------------------------------------------------------------------
 *   @EnableWebSecurity                            @EnableWebFluxSecurity
 *   HttpSecurity                                  ServerHttpSecurity
 *   SecurityFilterChain                           SecurityWebFilterChain
 *   web.cors.CorsConfigurationSource              web.cors.reactive.CorsConfigurationSource
 *   web.cors.UrlBasedCorsConfigurationSource      web.cors.reactive.UrlBasedCorsConfigurationSource
 *
 * The two families fail differently, and neither message says "you used the servlet
 * version". HttpSecurity fails at STARTUP with "No qualifying bean of type
 * ...web.builders.HttpSecurity available", because nothing in a reactive app ever
 * publishes one. The CORS pair fails a step later, on bean instantiation, with
 * "NoClassDefFoundError: jakarta/servlet/ServletRequest" - the class itself is on the
 * classpath (spring-web ships both), it just cannot be LOADED without the servlet API,
 * which a WebFlux app does not have. Only the package name distinguishes them, so an
 * IDE auto-import lands on the wrong one about half the time.
 *
 * One thing this chain deliberately does NOT do is propagate identity downstream.
 * product/user/order have no security on the classpath and still trust the X-User-ID
 * header, so authenticating here is only half the job: without more, a caller holding
 * any valid token could still send someone else's X-User-ID. That half is
 * {@link UserIdRelayFilter}, which strips the inbound header and rewrites it from the
 * token. Authentication and identity propagation are separate concerns and separate
 * classes; this one decides WHETHER a request proceeds, that one decides WHO it says
 * it is from.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http
                //Safe to disable ONLY because this is a stateless resource server:
                //authority comes from the Authorization header, which a browser does
                //not attach automatically, so there is nothing for a cross-site form
                //post to ride on. It would be unsafe the moment a session cookie or
                //HTTP Basic is added.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                //Delegates to the CorsConfigurationSource bean below. Doing it inside
                //the security chain matters: the CORS filter has to run BEFORE the
                //authorization check, otherwise the browser's preflight OPTIONS - which
                //carries no Authorization header - is rejected with a 401 and the real
                //request is never sent.
                .cors(Customizer.withDefaults())
                .authorizeExchange(auth -> auth
                        //Actuator has to be excluded explicitly, and finding that out
                        //the hard way is worth recording. management.server.port is 9090
                        //(shared application.yml), so actuator is served by a SEPARATE
                        //Netty server in a child context - which makes it look like this
                        //chain could not possibly apply to it. It does. Measured:
                        //  curl -i http://localhost:9090/actuator/health/liveness
                        //  -> HTTP/1.1 401  WWW-Authenticate: Bearer
                        //That "Bearer" challenge is the tell - it can only have come
                        //from the .oauth2ResourceServer(...) below, i.e. from this bean,
                        //not from any actuator default.
                        //
                        //Without this line the compose healthcheck
                        //(curl -f .../actuator/health/liveness) gets a 401, curl -f exits
                        //22, and cloud-gateway is marked unhealthy forever while the
                        //application is perfectly healthy - the same shape of bug as
                        //details.md §16 and §16.1, reached a third way.
                        //
                        //This does leave actuator unauthenticated, but that is the status
                        //quo ante, not a regression: port 9090 is unpublished except as
                        //127.0.0.1:7073 in docker-compose.yml, which is what §16.2 relies
                        //on. Requiring a token here instead would mean teaching the
                        //healthcheck to fetch one on every probe.
                        .pathMatchers("/actuator/**").permitAll()
                        //The Eureka dashboard route from §15. A browser cannot attach a
                        //bearer token to an address-bar navigation, so requiring one here
                        //does not secure the page, it just makes it unreachable. Both
                        //patterns are needed for the same reason GatewayConfig declares
                        //two routes: /eureka serves the page and /eureka/** serves the
                        //relative CSS/JS it asks for.
                        .pathMatchers("/eureka", "/eureka/**").permitAll()
                        //Signup, and only signup. POST /api/users is what CREATES the
                        //Keycloak account, so requiring a token here is circular: no
                        //account means no token means no way to make an account.
                        //Matched on METHOD + path so GET /api/users (list every user)
                        //stays behind authentication.
                        .pathMatchers(HttpMethod.POST, "/api/users").permitAll()
                        //Everything else - every proxied API route - needs a valid token.
                        //Still authenticated() rather than hasRole("CUSTOMER"): a bug in
                        //the converter below would otherwise 403 every route at once and
                        //look like a broken gateway. Tighten once the log line in
                        //grantedAuthoritiesExtractor shows the role actually arriving.
                        .anyExchange().authenticated()
                )
                //Bearer tokens only, validated as JWTs against the Keycloak keys named
                //by spring.security.oauth2.resourceserver.jwt.* in cloud-gateway.yml.
                //The converter is not optional if roles are wanted: with the default,
                //authorities come from the "scope"/"scp" claim as SCOPE_*, Keycloak's
                //roles are never read, and every .hasRole(...) rule silently denies.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(grantedAuthoritiesExtractor()))
                )
                .build();
    }

    /**
     * Consumed by .cors(...) above, which resolves it by TYPE - so it has to be the
     * reactive CorsConfigurationSource. A servlet one is not merely unused: it cannot
     * be instantiated at all in this application.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        //Browser ORIGINS that are allowed to call the gateway: scheme + host + port,
        //never a path. "http://localhost:8443/**" was both - a path makes the value
        //unmatchable, since it is compared against the Origin header verbatim, and 8443
        //is Keycloak, which is not a browser origin that calls this API. A front end
        //talks to Keycloak by being redirected to it; the gateway never sees that.
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        //Not needed for a bearer-token flow (no cookies are sent), but harmless while
        //the origins are an explicit list. Note it is incompatible with the "*" origin
        //wildcard: allowCredentials(true) plus setAllowedOrigins("*") throws at request
        //time, and setAllowedOriginPatterns is the escape hatch if that is ever wanted.
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Turns Keycloak's realm roles into Spring authorities.
     *
     * <p>REALM roles ({@code realm_access.roles}), not client roles
     * ({@code resource_access.<clientId>.roles}). Realm roles need no client UUID to
     * assign - which is what let user-service drop its {@code client-uid} property - and
     * a built-in mapper puts them in every access token, whereas client roles only
     * appear when that client is in the token's audience. One fewer way for roles to
     * vanish silently.
     *
     * <p>The {@code ROLE_} prefix is added HERE, so the role must be named
     * {@code CUSTOMER} in Keycloak, not {@code ROLE_CUSTOMER}. Spring's
     * {@code hasRole("CUSTOMER")} compares against the authority {@code ROLE_CUSTOMER};
     * naming the realm role with the prefix yields {@code ROLE_ROLE_CUSTOMER}, which
     * matches nothing and fails as a 403 rather than as an error.
     *
     * <p>Both null checks are load-bearing, not defensive padding. A client-credentials
     * token has no {@code realm_access} at all, and a user with no roles has no
     * {@code roles} key - {@code getClaimAsMap} returns null in the first case and
     * {@code get("roles")} returns null in the second. Either one used to be an NPE
     * inside the converter, i.e. a 500 on every request from a role-less user, when the
     * correct outcome is authentication with zero authorities.
     */
    @SuppressWarnings("unchecked")
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> grantedAuthoritiesExtractor() {
        ReactiveJwtAuthenticationConverter jwtAuthenticationConverter = new ReactiveJwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            List<String> roles = realmAccess == null
                    ? List.of()
                    : (List<String>) realmAccess.getOrDefault("roles", List.of());
            //info while roles are being brought up - this is the evidence that the
            //claim actually arrives. Expect Keycloak's defaults alongside CUSTOMER:
            //default-roles-<realm>, offline_access, uma_authorization. Drop to debug
            //once the rules below are tightened; it logs on every request.
            logger.info("Extracted roles for sub {}: {}", jwt.getSubject(), roles);
            return Flux.fromIterable(roles)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        });
        return jwtAuthenticationConverter;
    }
}
