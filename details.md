# ecom_microservices — Project Details

A learning/reference e-commerce backend built as Spring Boot microservices, with
Spring Cloud Config (native profile), Netflix Eureka service discovery, Spring
Cloud Bus for live config refresh, and a separate Grafana Loki + Alloy stack for
centralized log aggregation.

**On brokers:** this document was written as a change log, so earlier sections
describe RabbitMQ in the present tense. It is gone. §21–§23 moved the domain event
to Kafka; §28 moved the Bus there too and removed the container. Kafka is now the
only broker, carrying both flows on two topics.

**Shared versions across every Java module:** Spring Boot **4.1.0**, Spring
Cloud **2025.1.2**, Java **25**, MapStruct **1.6.3** (where used), Lombok
(where used).

---

## Contents

This is a change log, so it reads in order and **later sections supersede earlier
ones** — where §N and §M disagree, the higher number wins. Sections that were
overtaken carry a pointer forward at the point where they went stale.

**Foundations**

- [§1. Overview](#1-overview)
- [§2. Architecture](#2-architecture)
- [§3. config-server](#3-config-server-configserver)
- [§4. eureka-server](#4-eureka-server-eureka)
- [§5. Shared config for all client services](#5-shared-config-for-all-client-services)

**The services**

- [§6. product-service](#6-product-service-product)
- [§7. user-service](#7-user-service-user)
- [§8. order-service](#8-order-service-order)
- [§9. docker-compose.yml (main stack)](#9-docker-composeyml-main-stack)

**Observability**

- [§10. Observability stack (`evaluate-loki/`)](#10-observability-stack-evaluate-loki)
- [§11. Fixes to the Loki/Alloy log-scraping pipeline](#11-this-sessions-fixes-to-the-lokialloy-log-scraping-pipeline)
- [§12. Known inconsistencies worth being aware of](#12-known-inconsistencies-worth-being-aware-of)
- [§13. Observability stack v2: `evaluate-prometheus/`](#13-observability-stack-v2-evaluate-prometheus)
- [§14. Distributed tracing with Zipkin](#14-distributed-tracing-with-zipkin)

**The gateway**

- [§15. API gateway](#15-api-gateway-gateway)
- [§16. Compose hardening — healthchecks, actuator port split, network hygiene](#16-compose-hardening--healthchecks-actuator-port-split-network-hygiene)

**Resilience**

- [§17. Client-side load balancing — `@LoadBalanced` and `lb://`](#17-client-side-load-balancing--loadbalanced-and-lb)
- [§18. Circuit breakers in order-service — `@CircuitBreaker` + YAML](#18-circuit-breakers-in-order-service--circuitbreaker--yaml)
- [§19. Circuit breakers in cloud-gateway — route filters + YAML](#19-circuit-breakers-in-cloud-gateway--route-filters--yaml)
- [§20. Rate limiting in cloud-gateway — `RedisRateLimiter`](#20-rate-limiting-in-cloud-gateway--redisratelimiter)

**Messaging — three takes on the same event**

- [§21. Asynchronous messaging — RabbitMQ](#21-asynchronous-messaging--rabbitmq-order-service--notification-service)
- [§22. Take 2 — Spring Cloud Stream over the same RabbitMQ](#22-messaging-take-2--spring-cloud-stream-over-the-same-rabbitmq)
- [§23. Take 3 — Spring Cloud Stream over Kafka](#23-messaging-take-3--spring-cloud-stream-over-kafka)

**Security and outbound HTTP**

- [§24. Keycloak — authentication, identity propagation, provisioning](#24-keycloak--authentication-identity-propagation-and-user-provisioning)
- [§25. Outbound HTTP — two client shapes, and which one to reach for](#25-outbound-http--two-client-shapes-and-which-one-to-reach-for)

**Front end, and closing the authorization gap**

- [§26. The React front end — PKCE in a browser](#26-the-react-front-end--pkce-in-a-browser-and-what-the-backend-owed-it)
- [§27. Roles, `/me`, and one type for `productId`](#27-closing-the-authorization-gap--roles-me-and-one-type-for-productid)

**Consolidation**

- [§28. One broker — Spring Cloud Bus onto Kafka, and what a refresh really does](#28-one-broker--spring-cloud-bus-onto-kafka-and-what-a-refresh-really-does)

---

## 1. Overview

Seven Spring Boot modules, each its own Maven project (no shared parent POM —
each declares `spring-boot-starter-parent` directly), plus one non-Java module:

| Module | Port | Datastore | Responsibility |
|---|---|---|---|
| `configserver/` | 8888 | — (reads local YAML files) | Spring Cloud Config Server, native profile |
| `eureka/` | 8761 | — | Netflix Eureka service registry |
| `gateway/` | 8080 | — (Redis for rate-limit buckets) | Spring Cloud Gateway, OAuth2 resource server, the only published entry point (§15, §24.2) |
| `product/` | 8081 | PostgreSQL | Product catalog CRUD |
| `user/` | 8082 | MongoDB | User/account CRUD, provisions Keycloak accounts (§24.7) |
| `order/` | 8083 | PostgreSQL | Shopping cart + order placement, calls product-service and user-service |
| `notification/` | 8084 | — | Kafka consumer of `OrderCreatedEvent`; no HTTP API, no database (§23) |
| `frontend/` | 5173 | — | React + Vite SPA, PKCE login, admin UI, Vite dev server only (§26, §27.7) |

The first three rows are infrastructure and the next four are the business
services. This table was written when there were five modules and is corrected
here — gateway arrived in §15, notification in §21, the front end in §26.

Plus a sibling, independently-run stack in `evaluate-loki/` (official Grafana
Loki example, lightly modified) for log aggregation and viewing.

**Typical request flow:** a client calls `order-service` (e.g. `POST
/api/carts`). `order-service` validates the product and user by calling
`product-service` and `user-service` over HTTP, resolving their addresses via
Eureka (`http://product-service`, `http://user-service` — logical service IDs,
not hardcoded hosts) using a `@LoadBalanced RestClient`. All five services
pull their configuration from `config-server` at startup, and register
themselves with `eureka-server` so they can find each other.

---

## 2. Architecture

```
                         ┌───────────────┐
                         │  config-server │  :8888  (native profile,
                         │                │          serves configserver/.../config/*.yml)
                         └───────┬────────┘
                                 │ all services fetch config at startup
              ┌──────────────────┼──────────────────┬───────────────┐
              │                  │                   │               │
        ┌─────▼─────┐     ┌──────▼──────┐     ┌──────▼──────┐  ┌─────▼──────┐
        │ eureka-    │     │ product-    │     │ user-       │  │ order-     │
        │ server     │◄────┤ service     │     │ service     │  │ service    │
        │ :8761      │◄────┤ :8081       │     │ :8082       │◄─┤ :8083      │
        │ (registry) │◄────┴─────────────┴─────┴─────────────┴──┤ (calls the │
        └────────────┘        Postgres          MongoDB         │  other two │
                                                                  │  via Eureka)│
                                                                  └────────────┘
        Kafka ── Spring Cloud Bus ── /actuator/busrefresh broadcasts config
        changes to config-server, product, user and order (topic springCloudBus,
        one anonymous consumer group per service). Was RabbitMQ until §28.

  All of the above share the Docker network `ecom-network` (docker-compose.yml).

  ── separate stack, own docker-compose file, own network `loki` ──
        flog (fake logs) ─┐
        app containers  ──┼─► Alloy ─► gateway (nginx) ─► write/read/backend (Loki) ─► MinIO (S3-compatible storage)
        ./logs/*/*.log  ──┘                                                              ▲
                                                                                 Grafana ─┘ (Explore → Loki datasource)
```

---

## 3. config-server (`configserver/`)

**Role:** Spring Cloud Config Server running in `native` profile — instead of
pulling config from a Git repo, it serves YAML files straight off its own
filesystem.

- `spring.cloud.config.server.native.search-locations: file:/app/config`
  points at a directory that's bind-mounted from the host:
  `./configserver/src/main/resources/config:/app/config` (see
  `docker-compose.yml`). So editing a file under
  `configserver/src/main/resources/config/` on the host and restarting (or
  triggering a bus refresh) changes what every other service sees.
- Files served from that directory:
  - `application.yml` — **shared by every service** (see §5 below).
  - `product-service.yml`, `user-service.yml`, `order-service.yml`,
    `eureka-server.yml` — one per service, layered *on top of*
    `application.yml` (service-specific values win on conflict).
- Config-server's **own** bootstrap settings live in its *local*
  `src/main/resources/application.yaml` (not the config repo it serves) —
  this is the file that governs config-server itself before it can serve
  anything to anyone:
  ```yaml
  server:
    port: 8888
  spring:
    application:
      name: config-server
    profiles:
      active: native
    cloud:
      config:
        server:
          native:
            search-locations: file:/app/config
    rabbitmq:
      host: rabbitmq
      port: ${RABBITMQ_PORT}
      username: ${RABBITMQ_USER}
      password: ${RABBITMQ_PASS}
  eureka:
    client:
      serviceUrl:
        defaultZone: http://eureka-server:8761/eureka
      register-with-eureka: true
      fetch-registry: true
  management:
    endpoints:
      web:
        exposure:
          include: "*"
  logging:
    file:
      name: logs/${spring.application.name}.log
    logback:
      rollingpolicy:
        max-file-size: 5MB
        max-history: 7
  ```
  (The `eureka.client.serviceUrl.defaultZone` line above was fixed this
  session — see §8, item 4 — it previously pointed at `localhost`, which
  doesn't resolve inside a container.)
- Depends on RabbitMQ (`condition: service_healthy`) since it's wired for
  Spring Cloud Bus (`/actuator/busrefresh` broadcasts config changes to all
  connected services without restarting them).

---

## 4. eureka-server (`eureka/`)

**Role:** the Netflix Eureka service registry all other services register
with and discover each other through.

- `EurekaApplication.java`:
  ```java
  @SpringBootApplication
  @EnableEurekaServer
  public class EurekaApplication {
      public static void main(String[] args) {
          SpringApplication.run(EurekaApplication.class, args);
      }
  }
  ```
- Local `application.yaml` (minimal, same pattern as every other service —
  just names itself and points at config-server):
  ```yaml
  spring:
    application:
      name: eureka-server
    config:
      import: optional:configserver:${SPRING_CLOUD_CONFIG_URI}
      fail-fast: true
  ```
- Real settings come from `configserver/src/main/resources/config/eureka-server.yml`:
  ```yaml
  server:
    port: 8761
  eureka:
    client:
      register-with-eureka: false
      fetch-registry: false
  ```
  This **overrides** the shared `application.yml`'s `register-with-eureka:
  true` / `fetch-registry: true` (meant for client services) — a standalone
  Eureka server shouldn't register with or query itself.
- pom.xml: `spring-cloud-starter-netflix-eureka-server`,
  `spring-cloud-starter-config` (yes — the registry itself is a config
  client, bootstrapping its own port/eureka settings from config-server),
  `spring-boot-starter-actuator`. No Lombok/MapStruct/JPA — it's a pure
  registry app.
- Dockerfile: standard two-stage build (`eclipse-temurin:25-jdk` →
  `eclipse-temurin:25-jre`), `EXPOSE 8761`.

**docker-compose.yml wiring:** `depends_on: config-server (healthy)`, env
`SPRING_CLOUD_CONFIG_URI: http://config-server:8888`. No other service lists
`eureka-server` in `depends_on` — they only need it to be *reachable* by the
time they try to register (Eureka clients retry), not up before they start.

---

## 5. Shared config for all client services

`configserver/src/main/resources/config/application.yml` — applied to
product-service, user-service, order-service, eureka-server, config-server
(anything that's a config-client):

```yaml
management:
  server:
    port: 9090               # actuator on its own, unpublished port — see §16 item 2
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    shutdown:
      enabled: true          # lets Eureka clients deregister gracefully on shutdown
  tracing:
    export:
      zipkin:
        endpoint: http://zipkin:9411/api/v2/spans   # §14
    sampling:
      probability: 1.0       # Zipkin trace sampling — trace everything

eureka:
  client:
    serviceUrl:
      defaultZone: http://eureka-server:8761/eureka   # Docker service name, not localhost
    register-with-eureka: true
    fetch-registry: true

logging:
  file:
    name: logs/${spring.application.name}.log
  logback:
    rollingpolicy:
      max-file-size: 5MB
      max-history: 7
```

**Why `eureka-server` not `localhost` matters:** inside a container,
`localhost` refers to the container itself, not the `eureka-server`
container. This exact class of bug was found and fixed twice in this
project: once for `eureka-server`'s original `docker-compose.yml`/config
setup, and again for config-server's own local `application.yaml` (§3, §8).

**Logging:** every service writes to `logs/${spring.application.name}.log`
(a path relative to the container's `/app` working directory, so effectively
`/app/logs/<service>.log`). `docker-compose.yml` mounts each service's logs
subdirectory separately — e.g. `./logs/product-service:/app/logs` — so on the
host you get `logs/<service-name>/<service-name>.log` per service. This
per-service nesting is what tripped up the Alloy glob pattern (§8).

---

## 6. product-service (`product/`)

**Role:** product catalog CRUD, PostgreSQL-backed, no outbound calls to
other services (pure provider, consumed by order-service).

### Endpoints — `/api/products`

| Method | Path | Request | Response | Behavior |
|---|---|---|---|---|
| POST | `/api/products` | `ProductRequest` | `201`, `ProductResponse` | Create product |
| GET | `/api/products` | — | `200`, `List<ProductResponse>` | All **active** products only |
| GET | `/api/products/{id}` | `id: Long` | `200`/`404` | Fetch one active product |
| PUT | `/api/products/{id}` | `id: Long`, `ProductRequest` | `200`/`404` | Update mutable fields |
| DELETE | `/api/products/{id}` | `id: Long` | `204`/`404` | **Soft delete** — sets `active=false`, row stays |
| GET | `/api/products/search?keyword=` | query | `200`, `List<ProductResponse>` | Case-insensitive name search, `active=true AND stockQuantity>0` |

Plus a demo endpoint, `GET /api/product/demo/message` (`ProductConfigDemoController`,
`@RefreshScope`), returning `${app.product.message}` — exists to demonstrate
Spring Cloud Config live-refresh (edit `product-service.yml` on the config
server, hit `/actuator/refresh` or Bus refresh, value updates without a
restart).

### `Product` entity (table `products`)

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | `@Id @GeneratedValue(IDENTITY)` |
| `name`, `description`, `category`, `imageUrl` | `String` | |
| `price` | `BigDecimal` | |
| `stockQuantity` | `Integer` | |
| `active` | `Boolean` | defaults `true`, drives soft-delete |
| `createdAt`/`updatedAt` | `LocalDateTime` | `@CreationTimestamp`/`@UpdateTimestamp` |

**ID type: `Long`** (Postgres identity column) — important because
user-service's ID type is `String` (see §7); order-service has to handle
both.

### Config & dependencies

Local `application.yaml` just names itself and imports config-server (same
minimal pattern as every service). Real settings —
`configserver/.../config/product-service.yml`:
```yaml
server:
  port: 8081
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
app:
  product:
    message: "hello-product-v2"
```
Key pom.xml deps: `spring-boot-starter-data-jpa`, `postgresql` (runtime),
`spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`,
`spring-cloud-starter-bus-amqp`, `mapstruct` (+processor), `lombok`,
`spring-boot-starter-actuator`.

No `@RestControllerAdvice` — errors fall through to Spring Boot's default
error response.

---

## 7. user-service (`user/`)

**Role:** user/account CRUD, MongoDB-backed, no outbound calls to other
services (pure provider, consumed by order-service).

### Endpoints — `/api/users`

| Method | Path | Request | Response | Behavior |
|---|---|---|---|---|
| GET | `/api/users` | — | `200`, `List<UserResponse>` | All users, no filtering (no soft-delete concept here) |
| GET | `/api/users/{id}` | `id: String` | `200`/`404` | Fetch by **Keycloak user id** — see §24.3 |
| POST | `/api/users` | `UserRequest` | `201`, `UserResponse` | Creates the account in **Keycloak and** Mongo (§24.7). The one route the gateway leaves public; `role` is always `CUSTOMER` |
| PUT | `/api/users/{id}` | `UserRequest`, `id: String` | `200`/`404` | Updates name/email/phone/address (not role) |
| DELETE | `/api/users/{id}` | `id: String` | `200`/`404`/`500` | **Hard delete** — document is physically removed |

### `User` document (collection `user_table`)

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | `@Id` — **the Keycloak user id, i.e. the JWT `sub`**, not a generated ObjectId (§24.3) |
| `firstName`, `lastName`, `phone` | `String` | |
| `email` | `String` | `@Indexed(unique = true)` |
| `role` | `UserRole` (`CUSTOMER`/`ADMIN`) | defaults `CUSTOMER` |
| `address` | `Address` (embedded) | |
| `createdAt`/`updatedAt` | `LocalDateTime` | `@CreatedDate`/`@LastModifiedDate` (Mongo auditing) |

**ID type: `String`** — this is the other half of the cross-service ID
mismatch: product IDs are `Long`, user IDs are `String`. order-service's
`CartItem.userId`/`Order.userId` were changed from `Long` to `String` earlier
in this project specifically to match this (Hibernate auto-migrated the
Postgres column via `ddl-auto: update`). Since §24 that `String` is a Keycloak
UUID rather than a Mongo ObjectId — same type, different origin, and the change
is what makes `X-User-ID` resolve at all.

### Config & dependencies

Real settings — `configserver/.../config/user-service.yml`:
```yaml
server:
  port: 8082
spring:
  mongodb:
    uri: ${SPRING_MONGODB_URI}
logging:
  level:
    root: INFO
    org.springframework.data.mongodb.core: DEBUG
    org.springframework.boot.autoconfigure: DEBUG
```
Key pom.xml deps: `spring-boot-starter-data-mongodb`,
`spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`,
`spring-cloud-starter-bus-amqp`, `mapstruct` (+processor), `lombok`,
`spring-boot-starter-actuator`, `httpclient5` (§24.8).

§24 adds a `keycloak.admin.*` block to this file (server URL, realm, service-account
client id and secret) and a `GlobalExceptionHandler` — `DuplicateKeyException`→409,
`KeycloakAdminException`→502. `deleteUser` still has its own ad hoc try/catch
(`NoSuchElementException`→404, else→500).

---

## 8. order-service (`order/`)

**Role:** shopping cart + order placement. The only service that calls
other services — talks to product-service and user-service to validate carts
before committing them.

### Endpoints

`CartController` — `/api/carts` (all require header `X-User-ID`):

| Method | Path | Body | Response | Behavior |
|---|---|---|---|---|
| POST | `/api/carts` | `CartItemRequest {productId, quantity}` | `201` | Validates product + user + stock, adds/updates cart item |
| DELETE | `/api/carts/items/{productId}` | — | `204`/`404` | Remove one cart item |
| GET | `/api/carts` | — | `200`, `List<CartItemResponse>` | Validates user exists, returns their cart |

`OrderController` — `/api/orders` (requires `X-User-ID`):

| Method | Path | Response | Behavior |
|---|---|---|---|
| POST | `/api/orders` | `201` `OrderResponse` / `400` if cart empty | Converts cart → order, clears cart |

Plus a demo endpoint mirroring product-service's: `GET /api/order/demo/message`
(`OrderConfigDemoController`, `@RefreshScope`, reads `${app.order.message}`).

### Interservice HTTP clients (`clients/` package)

Declarative `@HttpExchange` interfaces backed by `RestClient` +
`HttpServiceProxyFactory` (not Feign, not WebClient):

```java
// ProductServiceClient
@GetExchange("/api/products/{id}")
ResponseEntity<ProductResponse> getProductById(@PathVariable("id") Long id);

// UserServiceClient
@GetExchange("/api/users/{id}")
ResponseEntity<UserResponse> getUserById(@PathVariable("id") String id);
```

The `clients/` package holds three `@Configuration` classes with a deliberate
split of responsibility:

| class | role |
|---|---|
| `RestClientConfig` | owns the shared builders — infrastructure |
| `ProductServiceClientConfig` | builds `ProductServiceClient` only |
| `UserServiceClientConfig` | builds `UserServiceClient` only |

`RestClientConfig` declares **two** `RestClient.Builder` beans:

```java
@Bean @Primary @Scope("prototype")
public RestClient.Builder restClientBuilder() {
    return restClientBuilderConfigurer.configure(RestClient.builder());
}

@Bean @LoadBalanced @Scope("prototype")
public RestClient.Builder loadBalancedRestClientBuilder() {
    return restClientBuilderConfigurer.configure(RestClient.builder());
}
```

and each client config only *consumes* one, by qualifier:

```java
@Bean
public ProductServiceClient productHttpInterface(@LoadBalanced RestClient.Builder builder) {
    RestClient restClient = builder.baseUrl("http://product-service").build();
    ...
}
```

**Why two builders exist:** Spring Cloud LoadBalancer only load-balances
`RestClient.Builder` beans qualified `@LoadBalanced` — that's what lets a
literal service ID like `http://product-service` resolve to a real
`host:port` via Eureka at request time. If there were only *one*
`RestClient.Builder` bean in the whole context (even a `@LoadBalanced` one),
Spring Cloud Netflix Eureka Client's *own* internal HTTP transport (used for
registration/heartbeats against eureka-server) would pick it up too — and
route its own calls through the load balancer, which fails right after that
service starts (Eureka hasn't got itself in its own registry). That's exactly
what broke order-service's registration earlier in this project. The fix:
keep a separate `@Primary`, plain (non-load-balanced) builder as the default
for anything that doesn't ask for `@LoadBalanced` explicitly, and qualify
every inter-service client injection point with `@LoadBalanced` by name.

Both client configs inject the *same* `@LoadBalanced` bean — there is one
`ApplicationContext`, and the `@Configuration` class a bean is declared in has
no visibility or scoping effect. That is exactly why the builders were moved
out of `ProductServiceClientConfig`: `UserServiceClientConfig` depended on a
bean owned by a class named after a different service, and nothing in the file
said so.

The `@Scope("prototype")` on both builders and the
`restClientBuilderConfigurer.configure(...)` call are explained in §17, along
with the mechanism that makes `@LoadBalanced` work and how it relates to the
gateway's `lb://` scheme.

### Error handling (`exceptions/` package)

`GlobalExceptionHandler` (`@RestControllerAdvice`), returns
`ErrorResponse(String error, String message)`:

| Exception | Status | `error` |
|---|---|---|
| `ProductNotFoundException` | 404 | `PRODUCT_NOT_FOUND` |
| `UserNotFoundException` | 404 | `USER_NOT_FOUND` |
| `InsufficientStockException` | 409 | `INSUFFICIENT_STOCK` |
| `RestClientException` | 503 | `DOWNSTREAM_UNAVAILABLE` |
| `IllegalStateException` | 503 | `DOWNSTREAM_UNAVAILABLE` (Spring Cloud LoadBalancer's "no instances available", e.g. right after a dependency restarts) |

`CartService.ensureUserExists(userId)` calls `userServiceClient.getUserById`
and translates a real `HttpClientErrorException.NotFound` into
`UserNotFoundException`; anything else (connection failure, 5xx) propagates
as-is to the generic `RestClientException`/`IllegalStateException` handlers,
so "user doesn't exist" and "user-service is down" surface as distinctly
different HTTP statuses rather than one generic error.

### Entities

- `CartItem`: `id (Long)`, `userId (String)`, `productId (String)`,
  `quantity (Integer)`, `unitPrice (BigDecimal)`, timestamps.
- `Order` (table `orders`): `id (Long)`, `userId (String)`, `totalAmount
  (BigDecimal)`, `status (OrderStatus)`, `items (List<OrderItem>)`,
  timestamps.
- `OrderItem`: `id (Long)`, `productId (Long)` — note: `Long` here, vs
  `String productId` on `CartItem` (order-service stores the cart-side
  product reference as a string but the order-line item as the numeric
  Postgres type product-service actually uses).
- `OrderStatus` enum: `PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED`.

### Config & dependencies

Real settings — `configserver/.../config/order-service.yml`:
```yaml
server:
  port: 8083
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
app:
  order:
    message: "hello-order-v2"
```
Key pom.xml deps: same as product-service, plus `spring-cloud-starter-loadbalancer`
(implicit via `@LoadBalanced` usage) and the HTTP-interface machinery
(`spring-boot-starter-webmvc`'s `RestClient`/`HttpServiceProxyFactory`, no
extra dependency needed — it's part of Spring Web).

---

## 9. docker-compose.yml (main stack)

> **Updated by §15, §16, §20, §21 and §23.** This section describes the original
> layout. Since then a `gateway` service and a `zipkin` service were added, the
> three business services stopped publishing host ports, actuator moved to port
> 9090, and eureka-server gained a healthcheck that other services now gate on
> (§15, §16). Then `redis-server` arrived for rate limiting (§20),
> `notification-service` for the messaging example (§21), and `kafka` as a second
> broker (§23). Read §16 alongside this, and the closing note below for the
> current service list.

Services, in dependency order: `postgres`, `mongodb`, `rabbitmq` →
`config-server` (depends on rabbitmq healthy) → `eureka-server` (depends on
config-server healthy) → `product-service`, `user-service`, `order-service`,
`gateway` (each depends on its datastore + config-server + rabbitmq +
eureka-server, all `condition: service_healthy`).

Every app service gets:
- `SPRING_CLOUD_CONFIG_URI` (from `.env`, resolving to
  `http://config-server:8888` — the container-internal address)
- A `./logs/<service-name>:/app/logs` volume — this is what feeds the Loki
  file-scrape pipeline (§10).
- DB/queue credentials sourced from `.env` (not detailed here — see the
  `.env` file itself, which this document deliberately does not read or
  reproduce).

All on network `ecom-network` (bridge driver). Named volumes:
`postgres_data`, `mongo_data`, `rabbitmq_data`.

### 9.1 Current state (after §20, §21 and §23)

Thirteen services, in dependency order:

```
postgres, mongodb, redis-server, rabbitmq, kafka          infrastructure
  └─ config-server            (waits on rabbitmq healthy)
       └─ eureka-server       (waits on config-server healthy)
            ├─ product-service, user-service               (+ their datastore)
            ├─ order-service                               (+ postgres, kafka)
            ├─ notification-service                        (+ kafka)
            └─ cloud-gateway                               (+ redis-server)
  zipkin                                                   independent
```

Two brokers now run at once, and the split is worth stating plainly because it
is not obvious from the file: **RabbitMQ carries Spring Cloud Bus (§16) and
Kafka carries application events (§23).** `config-server` still gates on
`rabbitmq` for the bus; `order-service` and `notification-service` gate on
`kafka` for `OrderCreatedEvent`. Removing RabbitMQ would break `/actuator/busrefresh`,
not messaging.

Named volumes are now `postgres_data`, `mongo_data`, `rabbitmq_data` and
`kafka-data` — note the last one breaks the `_` naming convention of the other
three, matching the `kafka` service's own style rather than the file's.
`redis-server` has no volume, deliberately: rate-limiter buckets are disposable.

Broker connection details follow the §21.1 split — topology in the config
server, addresses in compose environment variables:

| service | variables |
|---|---|
| order-service, notification-service | `SPRING_RABBITMQ_*` (bus), `KAFKA_BROKERS: kafka:9092` (§23.3) |
| cloud-gateway | `SPRING_DATA_REDIS_*` |

`KAFKA_BROKERS` overrides a `${KAFKA_BROKERS:localhost:29092}` default in the
config-server files, so the same config works from an IDE against the published
`29092` port and from a container against `kafka:9092`. That dual-address setup
is explained in §23.2.

---

## 10. Observability stack (`evaluate-loki/`)

> **Superseded by `evaluate-prometheus/`** (§13) — kept here for reference,
> but `evaluate-prometheus/` is the one to actually run going forward. Both
> stacks bind the same host ports, so don't run them at the same time.

A separate `docker-compose.yaml`, run alongside the main stack, based on
Grafana's official Loki example (`read`/`write`/`backend`/`gateway`/`minio`
microservices-mode Loki deployment) plus `grafana` and `alloy` for scraping.

- **Loki** (`loki-config.yaml`): `tsdb` schema, S3-compatible object storage
  backed by **MinIO**, `memberlist` for the ring, `replication_factor: 1`
  (single-node semantics even though it's split into read/write/backend
  targets).
- **gateway** (nginx): routes `/loki/api/*` reads to `read`, pushes to
  `write` — the single entry point both Alloy and Grafana talk to
  (`http://gateway:3100`).
- **Grafana**: auto-provisions a Loki datasource pointed at the gateway, with
  header `X-Scope-OrgID: tenant1` (Loki multi-tenancy — must match Alloy's
  `tenant_id` below or writes/reads land in different tenants and nothing
  matches up).
- **Alloy** (`alloy-local-config.yaml`) — three scrape pipelines, all
  forwarding to `loki.write "default"` (`http://gateway:3100/loki/api/v1/push`,
  `tenant_id = "tenant1"`):
  1. `discovery.docker "flog_scrape"` — discovers **every** container on the
     host via `/var/run/docker.sock` (not just `flog`, despite the name) and
     scrapes their stdout/stderr, labeling by container name.
  2. `local.file_match "system_logs"` — `/var/log/*.log` inside the Alloy
     container itself (mostly a leftover from the official example; nothing
     of note is mounted there).
  3. `local.file_match "loki_app_logs"` — `/logs/**/*.log`, meant to pick up
     the actual Spring Boot app log files written via the
     `./logs/<service>:/app/logs` volumes in the main `docker-compose.yml`.
     This is the pipeline that was broken and got fixed this session — see
     §11.

---

## 11. This session's fixes to the Loki/Alloy log-scraping pipeline

**Symptom:** Grafana's Loki datasource showed no label filters at all in the
Explore view — not even `container` (which comes from the
already-working Docker-discovery scrape).

Three compounding bugs in `evaluate-loki/`, found by reading
`alloy-local-config.yaml` and `docker-compose.yaml` line by line:

1. **Component-type typo — breaks Alloy's entire config, not just this
   pipeline.** `alloy-local-config.yaml` declared:
   ```river
   loki.file_match "loki_app_logs" {
       path_targets = [{"__path__" = "/logs/*.log"}]
       sync_period  = "5s"
   }
   ```
   `loki.file_match` isn't a real Alloy component — only `local.file_match`
   exists (correctly used by the other two blocks, `system_logs` and
   `parent_app_logs`). Worse, the very next block referenced it as
   `local.file_match.loki_app_logs.targets`, which can't resolve to anything
   declared under the (invalid) `loki.file_match` name. A config-validation
   error like this stops Alloy's *whole* pipeline from loading — which
   explains why even the previously-working `container` label had
   disappeared, not just the new `service_name`/`filename` labels. Fixed by
   renaming the component to `local.file_match "loki_app_logs"`.

2. **No filesystem access to the app logs at all.** The `alloy` service in
   `evaluate-loki/docker-compose.yaml` only mounted the config file and the
   Docker socket:
   ```yaml
   volumes:
     - ./alloy-local-config.yaml:/etc/alloy/config.alloy:ro
     - /var/run/docker.sock:/var/run/docker.sock
   ```
   There was no mount exposing the main project's `./logs` directory (one
   level up from `evaluate-loki/`) at `/logs` inside the container, so
   `local.file_match` could never find any files even with bug 1 fixed.
   Fixed by adding `- ../logs:/logs:ro`.

3. **Glob pattern didn't match the actual (nested) log layout.** The main
   `docker-compose.yml` mounts each service's logs into its own
   subdirectory — `./logs/product-service:/app/logs`,
   `./logs/order-service:/app/logs`, etc. — so files land at
   `logs/<service-name>/<service-name>.log` on the host, one directory
   deeper than the original pattern expected. `/logs/*.log` (single-star,
   single level) never matches `/logs/product-service/product-service.log`.
   Fixed by changing the glob to `/logs/**/*.log` — Alloy's `local.file_match`
   uses doublestar globbing, so `**` reaches into the per-service
   subdirectories. The existing `service_name`/`filename` relabel rules
   (which derive their values from the full `__path__` via regex) needed no
   changes — they work correctly once the pattern actually matches files.

**Net effect of all three fixes together:** Alloy's config now loads validly,
has filesystem access to the log directory, and its glob actually matches
the files that are there — so `local.file_match "loki_app_logs"` should now
discover files under every `logs/<service>/` subdirectory, tail them, and
push them to Loki labeled with `service_name` and `filename` (derived by the
existing `discovery.relabel "loki_app_logs"` rules), which should now appear
in Grafana's Loki label browser alongside the pre-existing `container` label.

**Related fix, found in the same pass (unrelated to Loki):**
`configserver/src/main/resources/application.yaml` (config-server's *own*
bootstrap config, not the config it serves to others) still had
`eureka.client.serviceUrl.defaultZone: http://localhost:8761/eureka`. Since
config-server runs inside `ecom-network` with `register-with-eureka: true`,
`localhost` there resolves to the config-server container itself, not
`eureka-server` — the exact same "localhost inside a container" bug already
fixed for eureka-server earlier in this project. Fixed to
`http://eureka-server:8761/eureka` to match the pattern used everywhere else.

**Not yet applied:** these fixes are in the files on disk but the `alloy` and
`config-server` containers have not been rebuilt/restarted yet in this
session — run `docker compose -f evaluate-loki/docker-compose.yaml up -d
--build alloy` and `docker compose up -d --build config-server` (from the
repo root) to pick them up, then re-check Grafana's label browser.

---

## 12. Known inconsistencies worth being aware of

- **Soft delete vs. hard delete**: product-service soft-deletes
  (`active=false`); user-service hard-deletes. `product-service`'s
  `updateProduct` also doesn't check `active`, so a "deleted" product can
  still be updated via `PUT`.
- **ID type mismatch**: product IDs are `Long`, user IDs are `String`
  (a Keycloak UUID since §24 — previously a Mongo ObjectId) — order-service's
  `CartItem`/`Order` entities store `userId` as `String` (matching user-service)
  and `productId` as `String` on `CartItem` but `Long` on `OrderItem` (matching
  product-service). §26.5 is where this stops being cosmetic: at the HTTP
  boundary JavaScript has no type to disagree with, so the front end has to
  stringify by hand. §26.2 at least made a malformed id answer `400` instead of
  `500`.
- **No centralized error handling in product-service** — it has no
  `@RestControllerAdvice` at all, so errors fall through to Spring Boot's default
  error body. order-service has had one throughout; user-service gained one in
  §24.8, though `deleteUser` still carries a hand-rolled try/catch.
- **`user-service.yml` sets `spring.mongodb.uri`**, not the more common
  `spring.data.mongodb.uri` — worth double-checking this is the property
  name Spring Data MongoDB actually binds to in this Spring Boot version,
  since it's easy to typo this particular key.
- **Mongo auditing (`@CreatedDate`/`@LastModifiedDate` on `User`)** requires
  `@EnableMongoAuditing` somewhere in the app context to actually populate —
  no such annotation was found in user-service, so these fields may silently
  stay `null`.
- **The shutdown endpoint is off, and has been since the Boot 4 upgrade.** The
  shared `config/application.yml` carried
  `management.endpoint.shutdown.enabled: true` with the comment "eureka
  deregistering gracefully". That is a **Boot 3** property. Boot 4 removed the
  per-endpoint `enabled` form: `PropertiesEndpointAccessResolver.resolveAccess`
  builds exactly one key, `"management.endpoint.%s.access"`, with no fallback,
  and the configuration metadata for 4.1.0 lists `management.endpoint.shutdown.access`
  and no `.enabled` sibling. `ShutdownEndpoint` is declared
  `@Endpoint(id = "shutdown", defaultAccess = Access.NONE)`, so with nothing
  binding, it stays NONE. Boot does not warn about unrecognised properties, so
  this failed silently on upgrade.

  Two consequences, pulling opposite ways. The graceful-deregistration path the
  comment describes **is not working** — services leave Eureka by lease
  expiry, not by shutting down cleanly. But the security exposure §16.2 was
  written to prevent is also smaller than that section claims: the most dangerous
  endpoint is currently unreachable regardless of which ports are published.

  The property has been replaced with a commented-out
  `management.endpoint.shutdown.access: unrestricted` plus an explanation, rather
  than silently enabled — turning it on is a real security decision, not a
  typo fix. Note the same `enabled` → `access` rename applies to every other
  endpoint; `cloud-gateway.yml` already uses the new form
  (`management.endpoint.gateway.access: read-only`), which is why the routes
  endpoint works while shutdown does not.

---

## 13. Observability stack v2: `evaluate-prometheus/`

`evaluate-prometheus/` is the successor to `evaluate-loki/` (§10) — same
Loki `read`/`write`/`backend`/`gateway`/`minio` topology, plus a new
`prometheus` service and a Grafana provisioning setup
(`grafana/datasources/datasources.yml`) that registers **two** datasources
instead of one: `Prometheus` (`http://prometheus:9090`, marked default) and
`Loki` (`http://gateway:3100`, same `X-Scope-OrgID`/`tenant1` header pattern
as before). Config files also moved into a `logging/` subfolder
(`logging/loki-config.yaml`, `logging/alloy-local-config.yaml`) instead of
`evaluate-loki`'s flat layout, and Prometheus's own config lives at
`prometheus/prometheus.yml`.

**This stack is meant to replace `evaluate-loki/`, not run alongside it** —
both bind host ports 3000 (Grafana), 3100 (gateway), and 3101/3102
(read/write), so starting both compose projects at once will fail on a port
conflict. Stop `evaluate-loki` before bringing this one up.

`prometheus/prometheus.yml` scrapes three jobs every 5s, each hitting
`/actuator/prometheus`:
```yaml
scrape_configs:
  - job_name: "user-service"
    static_configs:
      - targets: ["user-service:8082"]
        labels: { application: "user-service" }
  - job_name: "product-service"
    static_configs:
      - targets: ["product-service:8081"]
        labels: { application: "product-service" }
  - job_name: "order-service"
    static_configs:
      - targets: ["order-service:8083"]
        labels: { application: "order-service" }
```

### Bugs found and fixed getting this stack running

**1. Cross-compose-project network isolation.** `prometheus` needs to
resolve `user-service`/`product-service`/`order-service` by Docker Compose
service name, but those containers live in the *main* `docker-compose.yml`'s
project (network `ecom-network`), while `evaluate-prometheus` is a separate
compose project (network `loki`). Docker Compose namespaces the actual
network name per-project by default, even when the YAML network *key* is
spelled the same across files — so simply naming both networks `loki` (as
they already were) does nothing to connect them; two physically distinct
networks still get created. Renaming alone wouldn't fix it, and copying the
entire main `docker-compose.yml` into `evaluate-prometheus/` would just
duplicate Postgres/Mongo/RabbitMQ/app containers and drift out of sync over
time. Fix: give the main compose's network an explicit, unprefixed name so
it's addressable from any project —
```yaml
# docker-compose.yml
networks:
  ecom-network:
    name: ecom-network
    driver: bridge
```
— then declare it `external: true` in `evaluate-prometheus/docker-compose.yaml`
and attach only the `prometheus` service to it (Grafana, Alloy, and the Loki
components never talk to the app services directly, so they stay on `loki`
only):
```yaml
networks:
  loki:
  ecom-network:
    external: true

services:
  prometheus:
    ...
    networks:
      - loki
      - ecom-network
```

**2. Missing `/actuator/prometheus` endpoint.** `spring-boot-starter-actuator`
alone doesn't register that endpoint — it's conditional on a Prometheus
Micrometer meter registry being on the classpath, not just actuator's
autoconfiguration. Without it, every scrape would 404 and Prometheus would
show all three targets as `DOWN` even with networking fixed. Fix: added
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
to `product/pom.xml`, `user/pom.xml`, and `order/pom.xml`.

**3. Stale relative paths left over from the `evaluate-loki` copy/paste.**
`evaluate-prometheus/docker-compose.yaml`'s `read`, `write`, `backend`, and
`alloy` services still referenced `./loki-config.yaml` and
`./alloy-local-config.yaml` — `evaluate-loki`'s flat file layout — but in
`evaluate-prometheus/` those files actually live under `./logging/`. Since
the referenced host paths didn't exist, Docker silently created empty
phantom directories there and then tried to bind-mount them onto container
paths that are real *files* baked into the images (e.g.
`/etc/alloy/config.alloy`), which fails outright:
```
Error response from daemon: ... error mounting ".../evaluate-prometheus/alloy-local-config.yaml"
to rootfs at "/etc/alloy/config.alloy": ... not a directory: Are you trying to
mount a directory onto a file (or vice-versa)?
```
The `read`/`write`/`backend` Loki containers had the identical wrong path for
`loki-config.yaml` and would have hit the same class of failure (crash-looping
once Loki tried to read a directory as its config file), just without an
OCI-level error blocking container start. Fix: corrected all four volume
references to `./logging/loki-config.yaml` / `./logging/alloy-local-config.yaml`.
Also removed the two empty phantom directories Docker had already created
from the failed run (`alloy-local-config.yaml/`, `loki-config.yaml/`) — dead
clutter, confirmed empty before deleting.

### A red herring worth documenting

Right after the network fix, Grafana's datasource dropdown showed only
`Loki (default)` — no Prometheus, no error. This wasn't a provisioning bug:
`docker ps` showed the **old `evaluate-loki` Grafana container still running
and holding host port 3000**, so the browser had been talking to
`evaluate-loki`'s single-datasource Grafana the entire time, not the new
`evaluate-prometheus` one. Resolved by stopping `evaluate-loki` before
starting `evaluate-prometheus` (see the port-conflict note above — this is
the same underlying issue).

### Status as of this session

All three fixes are applied in the files on disk. `read`/`write`/`backend`/
`alloy` were already started once with the broken paths, so they need to be
recreated to pick up the corrected mounts:
```
docker compose -f evaluate-prometheus/docker-compose.yaml up -d
```
Then verify via `http://localhost:9090/targets` (all three jobs `UP`) and
Grafana's Loki label browser / Prometheus Explore queries (e.g.
`up{application="product-service"}`).

---

## 14. Distributed tracing with Zipkin

The third leg of observability, alongside logs (§10–11, Loki) and metrics
(§13, Prometheus): **traces** — following one request as it hops across
service boundaries. Answers "this cart request was slow — was it
order-service, its call to product-service, or its call to user-service?",
which neither logs nor metrics can show on their own.

### What was added

**1. A `zipkin` container** in the main `docker-compose.yml`:
```yaml
zipkin:
  image: openzipkin/zipkin
  container_name: zipkin
  ports:
    - "9411:9411"
  networks:
    - ecom-network
```
On `ecom-network` so the app containers can reach it by the hostname
`zipkin`. UI at `http://localhost:9411`. No volume — traces are held in
memory and lost on restart, which is fine for local development.

**2. One dependency per instrumented service** — `eureka/pom.xml`,
`product/pom.xml`, `user/pom.xml`, `order/pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-zipkin</artifactId>
</dependency>
```
No `<version>` — managed by the `spring-boot-starter-parent` 4.1.0 BOM.

**3. Shared tracing config** in
`configserver/src/main/resources/config/application.yml`, so every service
inherits it (same pattern as the Eureka and logging blocks in §5):
```yaml
management:
  tracing:
    export:
      zipkin:
        endpoint: http://zipkin:9411/api/v2/spans
    sampling:
      probability: 1.0
```

`config-server` itself is deliberately **not** instrumented — it has no
tracing dependency, since tracing the config fetch that happens before
tracing is configured is circular and not useful.

### How it works

```
HTTP request → order-service ──(RestClient)──→ product-service
                    │                                │
              creates span                     creates child span
              (traceId T, spanId A)            (traceId T, parent A)
                    │                                │
                    └──────────► zipkin ◄────────────┘
                        both report to :9411, joined by traceId
```

Three pieces have to line up:

- **Span creation** — `spring-boot-starter-actuator` (already present
  everywhere) provides Micrometer *Observation*, which wraps every inbound
  HTTP request. That alone produces observations but no traces.
- **A tracer + exporter** — this is what `spring-boot-starter-zipkin` adds.
  In Boot 4 it bundles `spring-boot-micrometer-tracing-brave` (the
  autoconfiguration), `micrometer-tracing-bridge-brave` (Brave tracer), and
  `spring-boot-zipkin` (`ZipkinAutoConfiguration`, the HTTP sender that
  reads the endpoint property). Confirmed by inspecting the built jar's
  `BOOT-INF/lib/`.
- **Context propagation across the hop** — outbound calls must carry the
  trace headers (`b3` / `traceparent`), or the downstream service starts an
  unrelated trace instead of a child span. This is why
  `order/src/main/java/com/ramesh/order/clients/ProductServiceClientConfig.java`
  builds its `RestClient.Builder` beans through
  `restClientBuilderConfigurer.configure(RestClient.builder())` rather than
  a bare `RestClient.builder()` — `RestClientBuilderConfigurer` applies
  Boot's autoconfigured `RestClientCustomizer` beans, including the
  observation/tracing one that injects those headers. A bare builder
  produces working HTTP calls whose traces silently break at every service
  boundary. (The `@Primary` plain builder vs. `@LoadBalanced` builder split
  in that same class is a separate concern — see §8.)

`sampling.probability: 1.0` records **every** request. Boot's default is
`0.1` (10%), which is right for production volume but makes a local demo
look broken — you'd hit an endpoint and usually see nothing.

### Why these exact values — two traps worth knowing

**Trap 1: the starter vs. raw libraries.** It is tempting to add
`micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` directly. Those
are the *instrumentation libraries*; they contain no Spring Boot
autoconfiguration, so nothing ever constructs a `Tracer` and **no traces
are produced, with no error at startup** — the app boots perfectly and
Zipkin simply stays empty. In Boot 4 the autoconfiguration lives in
separate `spring-boot-*` modules (the same modularization that split
`spring-boot-webmvc`, `spring-boot-restclient`, and
`spring-boot-micrometer-observation` out of the old monolithic starters).
`spring-boot-starter-zipkin` exists precisely to bundle the right set — use
it rather than assembling modules by hand.

Diagnostic for this class of failure: `GET /actuator/conditions` and look
for tracing autoconfiguration classes. If `ZipkinAutoConfiguration` appears
in neither `positiveMatches` nor `negativeMatches`, the class isn't on the
classpath at all — a missing-module problem, not a config problem.

**Trap 2: the endpoint property moved in Boot 4.** The correct key is
`management.tracing.export.zipkin.endpoint`. Many guides (and Boot 3.x
itself) use `management.zipkin.tracing.endpoint` — under Boot 4 that key
binds to nothing, is silently ignored, and the exporter falls back to its
default `http://localhost:9411/api/v2/spans`. Inside a container
`localhost` is *that container*, not the `zipkin` container, so every span
flush fails quietly and Zipkin stays empty. Identical in shape to the
`localhost`-vs-service-name bugs in §5, but harder to spot because the
symptom is a *silently ignored property* rather than a wrong value.

Diagnostic: `GET /actuator/configprops` and search the bound prefixes. The
real prefix appears as `management.tracing.export.zipkin` with an
`endpoint` property; `management.zipkin.tracing` does not appear at all.
This is authoritative in a way documentation and memory are not — it
reports what *this* Boot version actually binds. Note that
`/actuator/env/<key>` is **not** a valid check here: it happily reports any
key present in a property source, whether or not anything binds it.

### Verification

1. `docker compose up -d --build eureka-server product-service user-service order-service`
   (rebuild required — dependency change, not just config).
2. `curl http://localhost:8081/api/products` a few times to generate traffic.
3. `curl http://localhost:9411/api/v2/services` → should list
   `["eureka-server","order-service","product-service","user-service"]`.
4. For a true multi-service trace, call an endpoint that crosses a
   boundary — `POST /api/carts` (order-service → product-service and
   user-service) — then open `http://localhost:9411`, search by
   `serviceName=order-service`, and confirm a single trace containing spans
   from more than one service. One trace with spans from several services
   is the proof that propagation (the `RestClientBuilderConfigurer` piece)
   is working; several single-service traces for one logical request means
   it is not.

### Note on config-only changes

Because `config-server` serves this file live off a bind mount
(`./configserver/src/main/resources/config:/app/config`), editing the
tracing block needs no config-server rebuild — but the client services read
config only at startup, so they still need a restart:
```
docker compose restart order-service product-service user-service eureka-server
```
`docker compose up -d` alone will **not** pick up a `pom.xml` change — that
recreates containers from the existing image. Dependency changes need
`--build`, and Docker's build cache has been observed serving a stale layer
even then; `docker compose build --no-cache <service>` is the reliable
escape hatch.

> **Port note:** the verification commands above use `localhost:8081` and
> `localhost:9411`. As of §16, the service ports 8081/8082/8083 are no longer
> published to the host — generate traffic through the gateway
> (`localhost:8080/api/products`) instead. Zipkin on 9411 is unchanged.

---

## 15. API gateway (`gateway/`)

A Spring Cloud Gateway service added as the single front door to the system.
Before it, every client had to know each service's host port; now one origin
(`localhost:8080`) fronts all three business services, and the services
themselves are private to the Docker network (§16, item 6).

### What it is

`GatewayApplication.java` is a bare `@SpringBootApplication`; all behaviour
comes from `config/GatewayConfig.java` plus the config-server-served YAML.

`gateway/pom.xml` (Boot 4.1.0, Spring Cloud 2025.1.2, Java 25 — same as
every other module):

| dependency | role |
|---|---|
| `spring-cloud-starter-gateway-server-webflux` | the gateway itself, **reactive** flavour |
| `spring-boot-starter-webflux` | reactive stack (Netty) the gateway runs on |
| `spring-cloud-starter-loadbalancer` | resolves `lb://` URIs to instances |
| `spring-cloud-starter-netflix-eureka-client` | supplies the instance list |
| `spring-cloud-starter-config` | fetches config from config-server |
| `spring-boot-starter-actuator` | health endpoint for the compose healthcheck |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | route-level circuit breakers (§19) — **reactor** variant, not the one order-service uses |
| `spring-boot-starter-zipkin` | tracing — same starter as §14 |
| `micrometer-registry-prometheus` (runtime) | `/actuator/prometheus` |

This is the **reactive** gateway; the project originally used the servlet one.
See "Migration: webmvc → webflux" at the end of this section for what changed
and why.

### Route configuration — Java fluent API

Routes live in `gateway/src/main/java/com/ramesh/gateway/config/GatewayConfig.java`
as a `RouteLocator` bean rather than in YAML:

```java
@Configuration
public class GatewayConfig {
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder){
        return builder.routes()
                .route("PRODUCT-SERVICE", r -> r
                        .path("/api/products/**")
                        .uri("lb://product-service"))
                .route("ORDER-SERVICE", r -> r
                        .path("/api/carts/**", "/api/orders/**")
                        .uri("lb://order-service"))
                // … USER-SERVICE, plus the two Eureka routes below
                .build();
    }
}
```

**Two independent strings per route, and only one of them matters for
discovery:**

- The first argument — `"PRODUCT-SERVICE"` — is the **route id**: an arbitrary
  label. It appears in logs, metrics tags, and `/actuator/gateway/routes`, and
  has *no* role in service lookup. `"banana"` would route identically. The
  uppercase convention here just mirrors the registry name for readability.
- The host portion of **`lb://product-service`** is the service ID looked up
  through the load balancer, which gets its instance list from Eureka.

That lookup is **case-insensitive**. Eureka uppercases `spring.application.name`
on registration, so the registry holds `PRODUCT-SERVICE` while the URI says
`product-service` — and it resolves fine (confirmed: `/api/products` → 200).

`lb://` is also why a hardcoded `http://product-service:8081` would be wrong
here: it would bypass discovery and break the moment a service ran more than
one instance.

**The varargs in `.path("/api/carts/**", "/api/orders/**")`** are the Java
equivalent of the YAML `Path=` shortcut's comma-separated list — one route
matching two prefixes, both owned by order-service (§8).

### Bootstrap vs. served config

Same two-file split every service uses (§3, §5):

`gateway/src/main/resources/application.yml` — baked into the jar, just
enough to find config-server:
```yaml
spring:
  application:
    name: cloud-gateway
  config:
    import: optional:configserver:${SPRING_CLOUD_CONFIG_URI}
    fail-fast: true
```

`configserver/src/main/resources/config/cloud-gateway.yml` — served at
runtime, matched to the application name. Now that routes live in Java, it
carries only the port and one actuator switch (the old YAML routes are kept
commented out in the file for reference):
```yaml
server:
  port: 8080

management:
  endpoint:
    gateway:
      access: read-only
```

### The rename, and the identifiers that must agree

The service was renamed `gateway` → `cloud-gateway`. Six identifiers are in
play and they are *not* all the same thing:

| identifier | value | set in |
|---|---|---|
| compose service | `cloud-gateway` | `docker-compose.yml` |
| container name | `ecom_gateway` (unchanged) | `docker-compose.yml` |
| `spring.application.name` | `cloud-gateway` | `gateway/src/main/resources/application.yml` |
| served config file | `cloud-gateway.yml` | must match the property above |
| Eureka registry ID | `CLOUD-GATEWAY` | derived, uppercased by Eureka |
| module directory | `gateway/` (unchanged) | — |

**The one coupling that will bite you:** config-server matches the served
filename to `spring.application.name`. Rename the property without renaming
the file and the service boots with **no config at all** — silently, because
the bootstrap import is `optional:` (see the gotcha below). It would come up
on the default port 8080 by luck, with whatever routes Java supplies, and no
error anywhere.

The compose rename had a second benefit: `gateway` no longer exists on
`ecom-network`, which removes a latent DNS ambiguity with
`evaluate-prometheus`'s nginx service — also named `gateway`. Prometheus is
attached to *both* networks, so a scrape target of `gateway:9090` would have
been ambiguous. Use `cloud-gateway:9090` or `ecom_gateway:9090`.

### Compose wiring

```yaml
  cloud-gateway:
    build:
      context: ./gateway
    container_name: ecom_gateway
    ports:
      - "${GATEWAY_PORT}:8080"
      # actuator, loopback-bound — see §16.2
      - "127.0.0.1:7073:9090"
    volumes:
      - ./logs/cloud-gateway:/app/logs
    depends_on:
      config-server:
        condition: service_healthy
      eureka-server:
        condition: service_healthy
    environment:
      SPRING_CLOUD_CONFIG_URI: ${SPRING_CLOUD_CONFIG_URI}
    healthcheck:
      # liveness, not /actuator/health — see §19.6
      test: [ "CMD", "curl", "-f", "http://localhost:9090/actuator/health/liveness" ]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    restart: unless-stopped
```

The gateway sits at the *top* of the dependency graph: it depends on
config-server (for its routes) and eureka-server (to resolve `lb://`), and
nothing depends on it. Deliberately it does **not** `depends_on` the three
business services — `lb://` is resolved per request, so a service that
starts later simply becomes routable when it registers. Adding those
dependencies would serialize startup for no benefit.

The `./logs/cloud-gateway:/app/logs` mount follows the same per-service
nesting as everything else, so gateway logs flow into the Loki pipeline
automatically (§5, §11) with no Alloy change. Note the log *file* is named
from `spring.application.name` (`cloud-gateway.log`), so the rename also
changed the Loki `service_name` label — Alloy derives it from the filename
(`regex = ".*/(.*)\\.log"`), not the directory. Older `gateway.log*` files
still exist under `logs/gateway/` and keep producing `service_name="gateway"`;
both values will appear in Grafana, the old one historical.

### Verified behaviour

| check | result |
|---|---|
| Eureka registry | registers as `CLOUD-GATEWAY` |
| `GET :8080/api/products` | `200` |
| `GET :8080/api/users` | `200` |
| `GET :8080/api/carts` | `400` — identical to calling order-service directly; that's the service's own validation, not a routing failure |
| `GET :8080/api/orders` | `405` — identical direct; proves the two-pattern route works |
| `GET :8080/eureka` | `200`, Eureka dashboard HTML |
| Zipkin | one traceId spanning `['cloud-gateway', 'product-service']`, and another spanning `['cloud-gateway', 'order-service']` |

That last row matters: it proves trace context propagates *across* the
gateway hop, so the `spring-boot-starter-zipkin` + `RestClientBuilderConfigurer`
machinery from §14 extends cleanly to the new front door. Traces recorded
before the rename remain under `gateway` in Zipkin.

### The Eureka dashboard route — a `rewritePath` lesson

Two routes proxy the Eureka dashboard through the gateway. They exist to
learn how `rewritePath` behaves, not because the dashboard needs proxying —
eureka-server is already published on 8761, and routing an operator tool
through the data-plane front door is questionable design (every future auth or
rate-limit filter has to carve out an exception).

```java
.route("EUREKA-SERVER", r -> r
        .path("/eureka")
        .filters(f -> f.rewritePath("/eureka", "/"))
        .uri("http://eureka-server:8761"))
.route("EUREKA-SERVER-STATIC", r -> r
        .path("/eureka/**")
        .uri("http://eureka-server:8761"))
```

**First bug found here:** the routes originally used
`.uri("http://localhost:8761")` and returned 500 with
`Connection refused: localhost/127.0.0.1:8761`. Inside the gateway container
`localhost` is the gateway itself. This is the third occurrence of that exact
class of bug in this project (§5) — the fix is always the Docker service name.

**Second, subtler bug — why the path is `/eureka` and not `/eureka/main`.**
The dashboard emits its assets as **relative** URLs:

```html
href="eureka/css/wro.css"    src="eureka/js/wro.js"
```

Served at `/eureka/main`, a browser resolves those against the base directory
`/eureka/` and requests `/eureka/eureka/css/wro.css`. Measured:

| request | at `/eureka/main` | at `/eureka` |
|---|---|---|
| page itself | 200 | 200 |
| `/eureka/css/wro.css` | 200 | 200 |
| `/eureka/eureka/css/wro.css` | **404** ← what the browser asked for | not requested |

At `/eureka` the base directory is `/`, so `eureka/css/wro.css` resolves to
`/eureka/css/wro.css` — which the passthrough route serves. The page renders
styled.

**Route order matters and is load-bearing:** `/eureka/**` also matches
`/eureka`. Spring Cloud Gateway preserves declaration order for equal-order
routes and first match wins, so `EUREKA-SERVER` must stay declared *before*
`EUREKA-SERVER-STATIC`.

**The general lesson:** `rewritePath` rewrites the *request* path. It cannot
touch URLs embedded in the *response body*. Any app emitting relative URLs
breaks when mounted at a deeper path than it thinks it occupies — and the
dashboard's absolute links, `Home` (`/`) and `last 1000 since startup`
(`/lastn`), still 404 through the gateway. No request-side filter can fix
those. Proxying a UI that was not built to run behind a prefix has a hard
ceiling.

One more consequence of §16: the dashboard's per-instance links now point at
`http://<container-hostname>:9090/actuator/info` — Eureka picks up
`management.server.port` automatically, but that port is unpublished, so those
links do not resolve from a host browser.

### Two gotchas

**`/actuator/gateway/routes` 404s by default — because *exposure* and
*enablement* are two different switches.** The shared config sets
`management.endpoints.web.exposure.include: "*"`, which is why it is tempting
to assume any missing endpoint doesn't exist. It does exist; it is simply
disabled by default in Spring Cloud Gateway 2025.1.x, and `include: "*"` only
controls whether an *enabled* endpoint is web-exposed. Confirmed by checking
the `/actuator` link list: no `gateway` entry appeared at all, meaning the
endpoint was never registered — a different signature from "registered but not
exposed".

Enabling it (in `cloud-gateway.yml`, since it applies only to this service):
```yaml
management:
  endpoint:
    gateway:
      access: read-only
```
Boot 4 uses `access` (`none` / `read-only` / `unrestricted`), superseding the
older `enabled: true`. Verified working — `docker exec ecom_gateway curl -s
http://localhost:9090/actuator/gateway/routes` now lists all five route ids
with their URIs, which is the fastest way to confirm what `GatewayConfig.java`
actually produced.

> An earlier version of this section claimed the servlet gateway "does not
> register" this endpoint and that the reactive one provides it. That was
> wrong: it is disabled by default on both, and the fix is the property above.

**`optional:` and `fail-fast: true` contradict each other.** The `optional:`
prefix tells Boot to tolerate an unreachable config-server, so `fail-fast`
never trips. For the gateway this is riskier than for other services: with no
config it would boot happily with *zero routes* on the default port and 404
everything, looking alive while being useless. Dropping `optional:` would make
it refuse to start instead. This pattern is project-wide (product, user,
order, eureka all have the identical pair), so it is listed here as a
consistency question rather than a gateway-specific defect. It is also what
makes the filename/`spring.application.name` coupling above fail *silently*
rather than loudly.

### Migration: webmvc → webflux

The gateway originally used the servlet flavour. What changed:

| | before | after |
|---|---|---|
| gateway starter | `spring-cloud-starter-gateway-server-webmvc` | `spring-cloud-starter-gateway-server-webflux` |
| web stack | `spring-boot-starter-webmvc` (Tomcat) | `spring-boot-starter-webflux` (Netty) |
| load balancer | transitive | `spring-cloud-starter-loadbalancer` declared explicitly |
| route config | YAML, `spring.cloud.gateway.server.webmvc.routes` | Java `RouteLocator` bean |

Confirmed in the boot log — two Netty servers, business and management:
```
NettyWebServer : Netty started on port 8080 (http)
NettyWebServer : Netty started on port 9090 (http)
```

**Route config is not portable between the two flavours.** The YAML property
path contains the flavour: `spring.cloud.gateway.server.webmvc.routes` vs
`...server.webflux.routes`. Moving the routes into Java sidesteps the question
entirely — `RouteLocatorBuilder` is the same API for both — which is one
practical argument for the fluent API over YAML.

**Why reverse the original "keep one HTTP stack" rationale.** The first
version of this section argued for webmvc on the grounds that every other
service is servlet-based, so a reactive gateway introduces a second, differently
behaving stack. That reasoning holds for the *business* services, which contain
blocking JDBC and Mongo calls where reactive buys nothing and complicates a lot.
The gateway is the one component where it inverts: it does no application work,
only I/O forwarding, so Netty's event loop handles many concurrent in-flight
requests without a thread each. The mixed-stack cost is real but confined to a
service with no business logic in it.

---

## 16. Compose hardening — healthchecks, actuator port split, network hygiene

Adding the gateway exposed a set of latent problems in `docker-compose.yml`.
None of them were causing a live outage; all of them were things that worked
by coincidence and would break under a small change. This section records
what changed, why, and how.

> This section records the state at the time of that work, when the gateway's
> compose service and application name were both `gateway`. Both are now
> `cloud-gateway` (§15) — the container name `ecom_gateway` did not change, so
> every `docker exec ecom_gateway …` command below still applies verbatim.

### The trigger: a healthcheck that could never pass

Adding `healthcheck: test: ["CMD", "curl", "-f", ...]` to `eureka-server`
left it permanently `unhealthy`. The cause:

```
docker exec ecom_eureka_server sh -c "curl ..."
sh: 1: curl: not found          →  exit 127
```

**`eclipse-temurin:25-jre` ships no `curl`, no `wget`, no `nc`** (only
`bash`). Docker treats any non-zero exit as a failed probe, so exit 127 —
command not found — is indistinguishable from "the application is down". The
probe can never succeed regardless of the app's actual state.

`configserver/Dockerfile` was the only Dockerfile that installed curl, which
is exactly why config-server was the only service reporting healthy. The fix
is one line, added to `eureka/Dockerfile` and `gateway/Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
```

**This had already cascaded into a real outage.** Because the gateway's
`depends_on` gates on `eureka-server: condition: service_healthy`, and that
condition could never be met, Compose *created* the gateway container and
never started it — `docker inspect` showed `started=0001-01-01` with empty
logs. A missing 5 MB package took the front door offline while every
individual service looked fine.

> **Note on probe timing:** the `interval: 30s` on the gateway and
> eureka-server probes does not delay startup detection. Docker probes every
> 5 s during `start_period` (`start_interval`, default 5 s) and marks the
> container healthy on the first success. Observed: gateway healthy 17 s after
> start, with a probe log of `exit=7, exit=7, exit=0`. The 5s-vs-30s
> inconsistency with config-server's probe is cosmetic.

### 1. RabbitMQ: host port used for an in-network connection

**What:** `SPRING_RABBITMQ_PORT: ${RABBITMQ_PORT}` → `SPRING_RABBITMQ_PORT: 5672`
in all four services, plus `port: ${RABBITMQ_PORT}` → `port: 5672` in
`configserver/src/main/resources/application.yaml`.

**Why:** `${RABBITMQ_PORT}` is the *host-side* port from the mapping
`"${RABBITMQ_PORT}:5672"`. But these services connect to hostname `rabbitmq`,
which only resolves *inside* `ecom-network` — the connection never traverses
the port mapping at all, and on that network RabbitMQ's AMQP port is always
5672. The two `${RABBITMQ_PORT}` references were unrelated numbers sharing a
variable name. It worked only because the mapping happened to be `5672:5672`;
changing the published port to dodge a local conflict would have broken all
four services with a confusing connection error.

The same file had a second form of this bug: `configserver`'s own
`application.yaml` referenced `${RABBITMQ_PORT}`, but compose never passes a
variable by that name to config-server (only `SPRING_RABBITMQ_*`). It booted
only because the `SPRING_RABBITMQ_PORT` environment variable takes precedence,
so the unresolvable placeholder was never evaluated. Remove the env var and
config-server would have failed to start on a placeholder error.

**The correct pattern was already in the file twice** — Mongo publishes
`${MONGO_PORT}` but the URI hardcodes `27017`; Postgres publishes `5433` but
the JDBC URLs hardcode `postgres:5432`. RabbitMQ was the outlier.

**Rule:** a host port belongs only in `ports:`. Anything addressing a
container by its service name must use the *container* port.

### 2. Actuator moved to an unpublished port 9090

**What:** added to both the shared config and config-server's own config:
```yaml
management:
  server:
    port: 9090
```
and repointed the three healthchecks (`config-server`, `gateway`,
`eureka-server`) at `http://localhost:9090/actuator/health`, plus the three
Prometheus targets in `evaluate-prometheus/prometheus/prometheus.yml` to
`<service>:9090`.

> Since amended: cloud-gateway's healthcheck now targets
> `/actuator/health/liveness` on the same port — see §19.6, which also corrects
> the reasoning originally given for that change. The port split described here
> is unchanged.
>
> Also since amended, and a bigger qualification: **two services now publish
> their actuator port to the host after all** — cloud-gateway on 7073 and
> order-service on 9083, both for browser access to
> `/actuator/circuitbreakers` (§18.9, §19.10). Both are bound to `127.0.0.1`
> rather than `0.0.0.0`, so the LAN exposure this section exists to prevent is
> still closed; only this machine can reach them. Read the rest of this section
> as describing the rule, and those two as deliberate, narrowly-scoped
> exceptions to it.

**Why:** the shared config combines `include: "*"` with what was then
`endpoint.shutdown.enabled: true` (deliberate at the time — it's how services
were meant to deregister gracefully from Eureka). Together with published host
ports, that meant an unauthenticated `POST http://localhost:8081/actuator/shutdown`
would stop product-service, and the same for 8080/8082/8083/8761. Fine on a
laptop bound to localhost; unacceptable anywhere shared.

> Correction, found later: that property has been inert since the Boot 4
> upgrade — Boot 4 removed the per-endpoint `enabled` form in favour of
> `access`, so `shutdown` is currently `Access.NONE` and the attack described
> above is not currently possible. See §12. This does **not** retire the port
> split. `include: "*"` still exposes `/actuator/heapdump`, which hands over the
> entire JVM heap — Postgres and RabbitMQ credentials included, in plaintext,
> regardless of how well `/actuator/env` sanitises its own output. That alone
> justifies keeping actuator off `0.0.0.0`.

**Why a separate port rather than trimming `include`:** moving the entire
management context to a port that no `ports:` block publishes preserves full
actuator access for the things that legitimately need it — container
healthchecks and Prometheus scraping, both of which run *inside*
`ecom-network` — while removing it from the host and LAN entirely. Nothing
had to be given up — or so it seemed: `shutdown` was assumed to still work for
graceful deregistration, which §12 shows it does not, for an unrelated reason.

**Both config files needed the change.** `configserver/src/main/resources/config/application.yml`
is served to config *clients*; config-server does not read the folder it
serves. Its own settings live in `configserver/src/main/resources/application.yaml`,
so that file needed the same block for its healthcheck to work on 9090.

No Dockerfile changes were needed: `EXPOSE` is documentation only and has no
effect on container-to-container traffic.

**Eureka status page URLs are handled automatically.** Spring Cloud Netflix
detects a differing management port and builds `statusPageUrl`/`healthCheckUrl`
from it, so the Eureka dashboard links stay correct.

### 3. Postgres healthcheck could go green before the databases existed

**What:** replaced the probe on `postgres`:
```yaml
test: ["CMD-SHELL", "psql -U ${POSTGRES_USER} -d ${ORDER_DB} -c 'SELECT 1' > /dev/null 2>&1 || exit 1"]
retries: 10
```
(was `pg_isready -U ${POSTGRES_USER}`, `retries: 5`.)

**Why:** `pg_isready` uses `PQping`, which reports the server as up **without
authenticating or opening the target database**. On a first boot the official
`postgres` entrypoint starts the server with `listen_addresses=''` — socket
only — runs `/docker-entrypoint-initdb.d/init-db.sql`, then restarts it for
real. `pg_isready` connects over that same socket and returns 0 *during* the
init window. Docker marks postgres healthy, `depends_on` releases, and
product/order can start before `product_db` / `order_db` exist.

A real query can't pass early. `${ORDER_DB}` is probed specifically because
`CREATE DATABASE order_db` is the **last** statement in `init-db.sql`, making
it the strictest available signal that initialization finished.

`psql` ships in `postgres:16`, healthchecks run as root, and local socket
connections are `trust` in the image's generated `pg_hba.conf` — no password
needed, and no credentials appear in the compose file.

**Note this was a latent race, not an observed failure.** It was masked
because the dependent services also wait on config-server and take ~20 s to
boot, and because it only applies to a first boot against an empty
`postgres_data` volume — on later starts the init scripts don't run at all.

### 4. Zipkin had no restart policy

**What:** added `restart: unless-stopped` to the `zipkin` service.

**Why:** it was the only service in the file without one (every other service
has it). Span export failures are silent by design — a dead Zipkin produces no
errors in any service log, so tracing would simply stop with no signal. The
`(healthy)` status Zipkin reports comes from a `HEALTHCHECK` baked into the
`openzipkin/zipkin` image, not from this compose file.

Deliberately **not** added: any `depends_on: zipkin`. Tracing must degrade
gracefully and never block application startup.

### 5. product / user / order now wait for Eureka

**What:** added to each of the three services' `depends_on`:
```yaml
      eureka-server:
        condition: service_healthy
```

**Why:** they previously started before Eureka was up and logged walls of
`Connection refused` / `Cannot execute request on any known server` stack
traces before self-healing ~30 s later. The Eureka client retries correctly,
so this was cosmetic — but it made real startup problems hard to spot in the
logs. Now possible only because eureka-server has a *working* healthcheck (see
the trigger above). Measured after the change: 0 occurrences of
`Cannot execute request on any known server` in all three services.

No dependency cycle is introduced: rabbitmq → config-server → eureka-server →
{product, user, order, gateway}.

### 6. Backend ports unpublished

**What:** deleted the `ports:` blocks from product-service (`8081`),
user-service (`8082`), and order-service (`8083`).

**Why:** with the gateway working, publishing the backends made it optional —
`localhost:8081/api/products` and `localhost:8080/api/products` both worked,
so nothing forced traffic through the front door. The containers still
`EXPOSE` their ports and remain fully reachable on `ecom-network`.

**What this does not break:** Prometheus scrapes over the Docker network
(`product-service:9090`), not via host ports. Inter-service calls resolve
through Eureka and `lb://`. Zipkin span export is outbound. **What it does
break:** direct Bruno/curl calls to `localhost:8081/8082/8083` — those must
now go through `localhost:8080`.

eureka-server (8761) and config-server (8888) remain published; they're
dashboards meant to be opened in a browser.

### 7. Two consistency fixes

- The Mongo URI addressed `@ecom_mongodb:27017` — the `container_name` —
  while every other cross-service reference in the file uses the compose
  *service* name (`rabbitmq`, `postgres`, `config-server`, `eureka-server`).
  Changed to `@mongodb:27017`. Both resolve; the service name doesn't break if
  `container_name` is ever changed or removed.
- `./configserver/src/main/resources/config:/app/config` is now mounted `:ro`.
  config-server only ever reads that directory, and it points straight at the
  source tree — a read-only mount removes any chance of a container writing
  into it.

### Verification

`docker compose up -d --build --wait` → exit 0. `--wait` blocks until every
service with a healthcheck reports healthy and exits non-zero if any fails,
which makes it a single command that validates items 2, 3 and 5 together.

| check | result |
|---|---|
| config-server AMQP | `Created new connection: …amqp://admin@172.19.0.3:5672/`, 0 connection errors |
| `localhost:8081/actuator/health` from host | `000` — refused, no longer published |
| `/actuator/health` on `:9090` in-network | `UP` for product, user, order, eureka, config-server |
| `/actuator/prometheus` on `:9090` | `HTTP 200`, 63/66/69 metric lines |
| postgres | `(healthy)` — the psql probe returns 0 |
| `Cannot execute request on any known server` | 0 in all three services |
| Eureka registry | `['CONFIG-SERVER','GATEWAY','ORDER-SERVICE','PRODUCT-SERVICE','USER-SERVICE']` |
| gateway routing after unpublishing | `/api/products` 200, `/api/users` 200, `/api/orders` 405 |
| Zipkin | trace spanning `['gateway', 'product-service']` |

Because the app containers lack `curl`, in-network actuator checks are run
from the gateway container, which has it:
```
docker exec ecom_gateway curl -sf http://product-service:9090/actuator/health
```

**Two items could not be fully exercised.** The first-boot Postgres race
(item 3) requires `docker compose down -v`, which destroys `postgres_data`
and `mongo_data` — only the probe's steady-state correctness is confirmed.
And the Prometheus target change was verified by confirming
`/actuator/prometheus` answers on `:9090` from inside the network, not by
watching Prometheus scrape it, since the `evaluate-prometheus` stack was not
running at the time.

### Consequences to remember

- **Actuator is no longer reachable from the host at all**, including
  `/actuator/health` and `/actuator/prometheus`. Use
  `docker exec ecom_gateway curl -s http://<service>:9090/actuator/...`.
  Publishing `9090` for a single service is a one-line change if that becomes
  tedious — at the cost of re-exposing `/actuator/shutdown` for it.
- **A config-server outage is now more disruptive.** Because
  `management.server.port` arrives *from* config-server, a service that boots
  without it leaves actuator on its business port and fails the `:9090` probe
  — showing unhealthy, which then blocks the gateway through `depends_on`.
  This is a loud failure rather than a silent misconfiguration, but it is a
  new coupling worth knowing about.
- **`${GATEWAY_PORT}` (8080) is now the only entry point** for `/api/**`.

### 16.1 The same healthcheck failure, again (added with §23)

This section opened with a healthcheck that could never pass — `curl` missing
from `eclipse-temurin:25-jre`, every probe exiting 127. Adding Kafka reproduced
it exactly, in an image that has nothing to do with Java:

```yaml
test: ["CMD", "kafka_broker-api-versions", "--bootstrap-server", "localhost:29092"]
```

`kafka_broker-api-versions` with an underscore; the binary in
`confluentinc/cp-kafka` is `kafka-broker-api-versions` with a hyphen. Every probe
returned `exec: "kafka_broker-api-versions": executable file not found in $PATH`,
so `ecom_kafka` sat at `Up (unhealthy)` indefinitely, and every service with
`depends_on: kafka: condition: service_healthy` refused to start. `docker compose
up -d` looked like Kafka failing to boot.

It was not. `docker logs ecom_kafka` said `Kafka Server started` and
`Awaiting socket connections on 0.0.0.0:9092` the whole time.

**The generalisable lesson, which the original §16 trigger did not state:** a
broken healthcheck and a broken service are indistinguishable from the outside,
because both leave the container unhealthy and its dependants unstarted. The
probe's own output is what separates them, and it is not in `docker logs` —
`docker logs` shows the *application's* output. It is in the container's health
record:

```
$ docker inspect ecom_kafka --format '{{json .State.Health}}'
... "ExitCode":-1, "Output":"OCI runtime exec failed: ... executable file not found in $PATH"
```

Read that before reading application logs whenever a container is `(unhealthy)`
but otherwise behaving. It answers "did the probe fail, or did the service fail?"
in one command.

Two rules of thumb this yields, both violated by the two failures above:

- **A healthcheck must be tested against the image, not written from memory.**
  `docker exec <container> <the exact command>` takes seconds and would have
  caught both the missing `curl` and the underscore.
- **Prefer a probe with no external dependency.** `eclipse-temurin` has no HTTP
  client at all, which is why §16 moved to a shell-based one. `cp-kafka` does
  ship its CLI tools, so the command form is fine here — only the spelling was
  wrong.

The third Kafka compose typo, `KAFKA_GROUP_INITIAL_REBLANCE_DELAY_MS`, belongs to
the same family and is recorded in §23.2: unknown `KAFKA_*` variables are dropped
by the image's configure script without complaint.

---

## 17. Client-side load balancing — `@LoadBalanced` and `lb://`

Two places in this project turn a **logical service name** into a real
`host:port`: order-service, when it calls product-service and user-service
directly, and cloud-gateway, when it forwards `/api/**`. They look like
different features but are the same library — **Spring Cloud LoadBalancer**,
which replaced Netflix Ribbon in Spring Cloud 2020.0 — reached through two
different entry points.

| | cloud-gateway | order-service |
|---|---|---|
| how it's triggered | `lb://` URI scheme | `@LoadBalanced` on the builder |
| component | `ReactiveLoadBalancerClientFilter` | request interceptor added to `RestClient.Builder` |
| stack | reactive (Netty / `ReactorLoadBalancer`) | blocking (`RestClient`) |
| where it's written | `GatewayConfig.java` | `RestClientConfig.java` |
| library | spring-cloud-loadbalancer | spring-cloud-loadbalancer |

Same jar, same Eureka registry cache, same round-robin default. Only the
integration point differs.

### Where the dependency actually comes from

Verified by reading the resolved POMs in `~/.m2`, not from memory.

`spring-cloud-starter-gateway-server-webflux` declares exactly three
dependencies — `spring-cloud-starter`, `spring-cloud-gateway-server-webflux`,
`spring-boot-starter-webflux`. **No load balancer.** The gateway starter alone
does not make `lb://` work.

`spring-cloud-starter-netflix-eureka-client` declares, non-optionally:
`spring-cloud-starter`, `spring-cloud-netflix-eureka-client`, Netflix's
`eureka-client`, and **`spring-cloud-starter-loadbalancer`**.

So every service here that talks to Eureka gets the load balancer for free.
The proof is in this repo: `order/pom.xml` contains **zero** loadbalancer
declarations (`grep -c loadbalancer order/pom.xml` → `0`) yet uses
`@LoadBalanced` successfully. Its only possible source is the Eureka starter.

`gateway/pom.xml` declares `spring-cloud-starter-loadbalancer` **explicitly**.
That is redundant — eureka-client would supply it anyway — but deliberate:
`lb://` is a *direct* use in `GatewayConfig.java`, and depending on it
arriving through Netflix Eureka is an invisible coupling. If discovery were
ever swapped or dropped, the explicit line is what keeps `lb://` legal.

Worth knowing what the failure would look like without it: the gateway
auto-configures `GatewayNoLoadBalancerClientAutoConfiguration$NoLoadBalancerClientFilter`,
which recognises the `lb` scheme and throws `NotFoundException` naming the
host. So it fails **at request time**, not at startup — the app boots happily
and every proxied call 503s.

### What it does on each request

```java
// order-service
RestClient restClient = builder.baseUrl("http://product-service").build();
```

`product-service` there is a **service ID**, not a hostname. The interceptor:

1. takes the URI host and treats it as a service ID
2. asks the `ServiceInstanceListSupplier`, backed by the locally cached Eureka
   registry, for live instances
3. picks one (`RoundRobinLoadBalancer` by default)
4. **rewrites the URI** to that instance's real address —
   `http://172.18.0.9:8081/api/products/7`

**A trap specific to Docker Compose:** the name `product-service` *does*
resolve here, because it is a Compose service on `ecom-network`. Without
`@LoadBalanced` the call would therefore not fail with "unknown host" — it
would go to Docker's resolved IP on **port 80**, where nothing listens, and
surface as connection-refused. The symptom points at networking; the cause is
a missing load balancer.

Three things follow from the URI rewrite:

- **No hardcoded ports.** §16 unpublished 8081/8082/8083, so the port now
  lives only in the served config (`product-service.yml: server.port: 8081`).
  Eureka carries it in the registration and callers never need to know it.
  Hardcoding `http://product-service:8081` would work today but re-couples
  every caller to that number.
- **Scaling.** `docker compose up -d --scale product-service=3` and calls
  round-robin across the three registrations with no config change.
- **Health awareness.** Eureka evicts instances that stop heartbeating and
  they drop out of the supplier's list. Plain Docker DNS round-robin has no
  such notion and would keep handing out a dead container's IP.

### How `@LoadBalanced` works internally

The annotation does nothing where it is written. Its own definition
(spring-cloud-commons 5.0.2) is:

```java
@Target({FIELD, PARAMETER, METHOD})
@Retention(RUNTIME)
@Documented
@Inherited
@Qualifier              // ← meta-annotated
public @interface LoadBalanced {}
```

Being meta-annotated `@Qualifier` is why the same annotation appears twice in
the `clients/` package doing **two unrelated jobs**:

- on the `@Bean` **method** — a marker that tags the bean *definition*
- on the injection **parameter** — a qualifier that selects that bean over the
  primary one

The marker is consumed by `LoadBalancerRestClientBuilderBeanPostProcessor`.
Its inherited `postProcessBeforeInitialization(bean, beanName)` does, in
effect:

```java
if (isSupported(bean)                                    // is it a RestClient.Builder?
    && applicationContext.findAnnotationOnBean(beanName, LoadBalanced.class) != null)
        ((RestClient.Builder) bean).requestInterceptor(loadBalancerInterceptor);
```

That last line is the crux: the post-processor **mutates the builder in
place**. Once mutated, that object is permanently load-balancing.

### Why there are two `RestClient.Builder` beans

Because of that in-place mutation, one bean cannot be both. The only way to
still have an ordinary builder is a **second object the post-processor never
touched**. The identical method bodies are irrelevant — what differs is the
metadata on each definition:

| bean name (= method name) | qualifier | primary? |
|---|---|---|
| `restClientBuilder` | — | ✅ |
| `loadBalancedRestClientBuilder` | `@LoadBalanced` | — |

`@Primary` is not decoration. Two beans of one type make every *unqualified*
injection point ambiguous — `NoUniqueBeanDefinitionException`, at startup.
`@Primary` names the default winner.

And the plain builder has to be declared at all because Boot's own
auto-configured one disappears the moment you declare any of your own. From
the bytecode of `RestClientAutoConfiguration` (spring-boot-restclient 4.1.0):

```java
@Bean
@Scope("prototype")
@ConditionalOnMissingBean
RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer)
```

So declaring only the `@LoadBalanced` bean would not have *added* a
load-balanced builder — it would have **replaced the only builder in the
context with a load-balanced one**. Every unqualified injection in the
application would then get it, and absolute URLs would fail, because the host
would be looked up as a service ID. That is precisely the failure recorded in
§8, where Eureka's own registration transport picked up the load-balanced
builder and could not route its own calls.

### Why both methods call `restClientBuilderConfigurer.configure(...)`

`RestClient.builder()` on its own is a bare builder with none of Boot's setup.
`RestClientBuilderConfigurer` applies request-factory detection, HTTP message
converters, and every `RestClientCustomizer` bean. That last one matters here
specifically: **Micrometer's tracing instrumentation registers as a
customizer.** Skipping the configurer would silently stop trace-context
propagation, and order → product calls would disappear from the Zipkin traces
described in §14 — a silent observability regression, not an error.

### `@Scope("prototype")` — why it was added

`RestClient.Builder` is **mutable**: `baseUrl()` sets a field and returns
`this`. Boot marks its own builder bean `@Scope("prototype")` for exactly that
reason.

Both beans were originally singletons, and two different `@Configuration`
classes consumed the same **instance**:

```java
// ProductServiceClientConfig
builder.baseUrl("http://product-service").build();

// UserServiceClientConfig  <- the same object
builder.baseUrl("http://user-service").build();
```

Each overwrote the other's `baseUrl`. It worked only because each sets and
then immediately `build()`s, single-threaded, during startup — `build()`
snapshots the current state into a new `RestClient`, so the later overwrite
came too late to matter. Correct by accident of ordering, not by design.
Anything that injected the builder and used it lazily, or from a second
thread, would send a request to the wrong service — an intermittent bug that
would present as a routing fault.

Both beans are now prototype-scoped, matching Boot:

```java
@Bean @Primary @Scope("prototype")
public RestClient.Builder restClientBuilder() { ... }

@Bean @LoadBalanced @Scope("prototype")
public RestClient.Builder loadBalancedRestClientBuilder() { ... }
```

Load balancing is unaffected: `postProcessBeforeInitialization` runs on every
instance created, prototypes included, and `findAnnotationOnBean` reads the
bean *definition*, not the instance. Each injection point simply gets its own
builder to mutate.

### Why east-west traffic does not go through the gateway

order-service could call `http://cloud-gateway:8080/api/products/{id}`
instead. It doesn't, and shouldn't: that adds a network hop and makes the
gateway a single point of failure for internal traffic as well as external.
The split this project follows is the conventional one — the gateway handles
**north-south** traffic (outside → in), and services call each other
**east-west** directly, with the load balancer supplying the addressing.

### Verification status

The `@Scope("prototype")` change compiles cleanly (`./mvnw -o compile`,
exit 0). It was **not** exercised at runtime — Docker Desktop was not running
at the time — so the round-trip check still to do after the next
`docker compose up -d --build order-service` is a cart operation that forces
both clients:

```
curl -X POST http://localhost:8080/api/carts \
     -H "X-User-ID: <userId>" -H 'Content-Type: application/json' \
     -d '{"productId":"1","quantity":1}'
```
(the user id travels in the `X-User-ID` header, not the path — see §8)

A 200 confirms both prototype builders resolve their service IDs through
Eureka. Every dependency and mechanism claim in this section was verified
against the resolved POMs and compiled bytecode in `~/.m2`.

### Two related notes

- **Organisation — resolved.** Both shared builders originally lived in
  `ProductServiceClientConfig`, while `UserServiceClientConfig` silently
  depended on them: renaming or deleting the product client would have broken
  the user client with a `NoSuchBeanDefinitionException` pointing at the wrong
  place. They now live in a neutral `RestClientConfig`, so each client config
  depends on shared infrastructure rather than on its sibling. Neither client
  config changed behaviourally — scope and qualifiers are properties of the
  bean *definition*, so moving the definitions was enough.
- **Prometheus.** There is still no scrape job for cloud-gateway in
  `evaluate-prometheus/prometheus/prometheus.yml`; adding one would target
  `cloud-gateway:9090`.

---

## 18. Circuit breakers in order-service — `@CircuitBreaker` + YAML

order-service is the only service here that calls other services (§17), so it is
the only one that can be left hanging when a dependency dies. Two breakers guard
those calls. Each one is declared with a resilience4j annotation on a bean that
does nothing but make the call, and **every setting lives in
`configserver/src/main/resources/config/order-service.yml`** — there is no Java
configuration for circuit breakers at all.

An earlier iteration used Spring Cloud's `CircuitBreakerFactory` with a Java
fluent-API `Customizer`. That version and the traps it avoided are kept in
§18.10, because one of them is still live: the registry-bean mistake breaks the
annotation approach just as badly.

### 18.1 What the dependency provides — and the one it does not

`order/pom.xml` declares `spring-cloud-starter-circuitbreaker-resilience4j` (the
**blocking** variant — correct for a webmvc service; cloud-gateway would need
`spring-cloud-starter-circuitbreaker-reactor-resilience4j`). Resolving its POM
chain shows it drags in far more than the Spring Cloud abstraction:

```
spring-cloud-starter-circuitbreaker-resilience4j 5.0.2
└── spring-cloud-circuitbreaker-resilience4j 5.0.2
    ├── resilience4j-circuitbreaker 2.3.0
    ├── resilience4j-timelimiter 2.3.0
    └── resilience4j-spring-boot3 2.3.0     ← compile scope, not optional
        ├── resilience4j-spring6           → the @CircuitBreaker aspect
        └── resilience4j-micrometer        → Prometheus metrics
```

What it does **not** bring is AspectJ — and without AspectJ the annotation is
inert. resilience4j declares its aspect bean like this
(`AbstractCircuitBreakerConfigurationOnMissingBean`, verified in the bytecode):

```java
@Bean
@Conditional(AspectJOnClasspathCondition.class)
public CircuitBreakerAspect circuitBreakerAspect(...)
```

and `AspectJOnClasspathCondition` tests for exactly one class:
`org.aspectj.lang.ProceedingJoinPoint`. Nothing in the tree above supplies it.

**The failure mode is silence.** With the class absent the aspect bean is
skipped, and everything else still works: the app starts, the `resilience4j.*`
YAML binds, `/actuator/circuitbreakers` lists both breakers, Prometheus exports
their metrics — and not one `@CircuitBreaker` ever intercepts anything. There is
no warning. The breakers simply sit at `CLOSED` with `bufferedCalls: 0` forever.

So `order/pom.xml` also declares:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

**Note the Boot 4 rename.** This was `spring-boot-starter-aop` through Boot 3,
and every resilience4j tutorial still says so. In Boot 4 it is
`spring-boot-starter-aspectj` — the same renaming that produced
`spring-boot-starter-webmvc` and `spring-boot-starter-restclient` elsewhere in
this POM. The old artifact id is not in the 4.1.0 BOM at all, so using it fails
the build immediately with `'dependencies.dependency.version' ... is missing`
rather than resolving something stale. An unhelpful message for a rename, but at
least it is loud.

The starter resolves to `spring-boot-starter` + `spring-aop` + `aspectjweaver
1.9.25.1`. Boot's `AopAutoConfiguration` then switches on, with
`proxyTargetClass` defaulting to true — which matters, because the two lookup
beans below implement no interface and can only be proxied by CGLIB.

### 18.2 Why the remote calls live in their own beans

`@CircuitBreaker` is applied by a **Spring AOP proxy**, and a proxy can only
intercept a call that arrives from outside the bean. This is the single most
important constraint on where the annotation can go, and it killed the obvious
placement:

```java
public void addToCart(...) {
    ...
    ensureUserExists(userId);   // straight down `this` — the proxy is never touched
}
```

Annotating `ensureUserExists` would have done nothing. **Making it `public` does
not help either** — visibility is irrelevant; what matters is that the call goes
through `this` rather than through the proxy reference Spring handed to
`CartService`'s callers.

There were two further problems with annotating `CartService.addToCart` itself:

- **The breaker would wrap the whole method.** `addToCart` calls product-service,
  *then* user-service, then checks stock, then writes to Postgres. Every one of
  those could trip a breaker named `product-service` — including
  `InsufficientStockException`, which is a business outcome, and any JPA error.
- **The fallback signature must match the return type.**
  `FallbackMethod.create(...)` matches candidates on name, parameter types
  (originals + `Throwable`) **and** return type via `isAssignableFrom`; with no
  match it throws `NoSuchMethodException` at call time. An earlier attempt had
  `addToCart` returning `void` and its fallback returning `boolean`, so the
  fallback could never have fired.

The fix is structural: give each remote call its own bean.

```
order/src/main/java/com/ramesh/order/clients/
├── ProductLookup.java   @CircuitBreaker(name = "product-service", ...)
└── UserLookup.java      @CircuitBreaker(name = "user-service", ...)
```

```java
@Component
@RequiredArgsConstructor
public class ProductLookup {
    private final ProductServiceClient productServiceClient;

    @CircuitBreaker(name = "product-service", fallbackMethod = "getProductFallback")
    public ProductResponse getProduct(Long productId) {
        ResponseEntity<ProductResponse> response = productServiceClient.getProductById(productId);
        return response == null ? null : response.getBody();
    }
    ...
}
```

`CartService` now calls `productLookup.getProduct(...)` and
`userLookup.getUser(...)` like ordinary collaborators. That is a **cross-bean**
call, so the proxy is crossed and the aspect runs. Two things follow:

- `ensureUserExists` can stay `private`, because the breaker is no longer on it.
- The protected region is exactly one HTTP call. The JPA work and the stock check
  are outside every breaker, which is what the "breaker wraps the whole method"
  objection above was really about.

`Long.valueOf(request.getProductId())` also stays *outside* the lookup, in
`CartService`: a malformed id is a bad request, not a product-service failure,
and must not count against the breaker.

The name in the annotation — `"product-service"` — is a plain literal that must
match an `instances:` key in the YAML. It is an arbitrary label, not a Eureka
service id; naming it after the service it protects is only a convention that
makes `/actuator/circuitbreakers` readable. **A name with no matching instance is
not an error**: resilience4j silently creates the breaker from `configs.default`
instead, so a typo shows up as a breaker quietly running the wrong settings
rather than as a startup failure.

### 18.3 The configuration — all of it in YAML

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        slow-call-duration-threshold: 4s
        slow-call-rate-threshold: 50
        register-health-indicator: true
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException$NotFound
    instances:
      product-service:
        base-config: default
      user-service:
        base-config: default
```

Why each value differs from the defaults documented at
<https://resilience4j.readme.io/docs/circuitbreaker>:

| property | default | here | reason |
|---|---|---|---|
| `sliding-window-type` | COUNT_BASED | COUNT_BASED | at low traffic a time-based window is usually empty, so rates never compute |
| `sliding-window-size` | 100 | 10 | |
| `minimum-number-of-calls` | **100** | 5 | with the default the breaker never opens in a demo — no rate is computed until 100 calls are recorded |
| `failure-rate-threshold` | 50% | 50 | |
| `wait-duration-in-open-state` | 60s | 10s | 60s makes the OPEN → HALF_OPEN transition tedious to observe |
| `permitted-number-of-calls-in-half-open-state` | 10 | 3 | |
| `automatic-transition-from-open-to-half-open-enabled` | false | true | left false, OPEN → HALF_OPEN only happens when a call *arrives* after the wait; true has a scheduler do it, so the transition shows up in actuator with no traffic |
| `slow-call-duration-threshold` | 60s | 4s | see §18.6 |
| `slow-call-rate-threshold` | 100% | 50 | |
| `register-health-indicator` | false | true | see below |
| `ignore-exceptions` | empty | `HttpClientErrorException$NotFound` | a 404 is a healthy service answering — see §18.4 |

Two details in that block are easy to get wrong:

- **`$` is the nested-class separator.** `NotFound` is an inner class of
  `HttpClientErrorException`, so the binder needs
  `...HttpClientErrorException$NotFound`.
- **`register-health-indicator: true` publishes breaker state into
  `/actuator/health`** as a `circuitBreakers` entry. It does **not**, by itself,
  turn the service DOWN while a breaker is OPEN — see §19.6, which works through
  `CircuitBreakersHealthIndicator` and Boot's `SimpleStatusAggregator` in detail.
  The short version: OPEN maps to a custom status `CIRCUIT_OPEN`, which is not in
  `Status.DEFAULT_ORDER`, so the aggregator filters it out and the overall status
  stays UP (HTTP 200). Setting `allow-health-indicator-to-fail: true` changes OPEN
  to a real `DOWN` and *is* the switch that makes an OPEN breaker fail a
  `curl -f` healthcheck. It is not set anywhere in this project. Also relevant if
  it ever is: `eureka.client.healthcheck.enabled` is unset (defaults false), so
  Eureka uses its own heartbeat and would not deregister the instance either way.

Because this file is served by the config server — and the config directory is
bind-mounted read-only into the container
(`./configserver/src/main/resources/config:/app/config:ro`) — these values can be
edited and pushed with `/actuator/busrefresh` without rebuilding **any** image.
That portability is the main practical argument for YAML over the fluent API: the
Java version required a rebuild of order-service to change a threshold.

### 18.4 Three places handle a 404 — and they are doing three different jobs

This is the part that looks like duplication and is not. Trace a single 404 from
user-service through the code:

| place | question it answers | affects the HTTP response? | affects breaker statistics? |
|---|---|---|---|
| YAML `ignore-exceptions` | does this failure count toward opening the breaker? | **no** | **yes** |
| `getUserFallback(..., NotFound ex)` | what exception should the caller see? | **yes** | no |
| `if (userLookup.getUser(id) == null)` | what if there was no exception at all? | **yes** | no |

**Step 1 — the breaker sees it first.** user-service returns 404, `RestClient`
throws `HttpClientErrorException.NotFound`, and
`CircuitBreakerStateMachine.handleThrowable` runs this three-way branch, in this
order (verified in the 2.3.0 bytecode):

```
ignoreExceptionPredicate.test(t)  → releasePermission(); publishCircuitIgnoredErrorEvent(); return
recordExceptionPredicate.test(t)  → publishCircuitErrorEvent();  state.onError()    // FAILURE
else                              → publishSuccessEvent();       state.onSuccess()  // SUCCESS
```

The YAML puts `NotFound` in branch 1: the permit is **released** and the call is
dropped from the sliding window entirely — recorded as neither success nor
failure. Note that it `return`s. It does not swallow. The exception carries on up.

**Step 2 — the fallback catches it.** The aspect wraps the decorated call in a
catch of `Throwable` and dispatches to the fallback for **every** exception,
ignored or not. So `getUserFallback(String, NotFound)` runs and swaps the
transport exception for `UserNotFoundException`, which `GlobalExceptionHandler`
maps to 404.

**Step 3 — the null check never sees it**, because we left by the exception path.
That check exists for the case where there is **no exception at all**:
user-service answers `200` with an empty body, or `204`. `getBody()` returns
`null`, no throwable exists, so neither the YAML nor the fallback can act. Without
it you would get a `NullPointerException` deep inside `addToCart` instead of a
404. Rare, but a genuinely different path.

**Why the fallback cannot be deleted.** Keep only `ignore-exceptions` and the
`NotFound` propagates untranslated into `CartService` and on to
`GlobalExceptionHandler`, where the only matching handler is:

```java
@ExceptionHandler(RestClientException.class)   // NotFound → HttpClientErrorException
                                               // → HttpStatusCodeException
                                               // → RestClientResponseException
                                               // → RestClientException
→ 503 DOWNSTREAM_UNAVAILABLE "A dependent service is unreachable"
```

"No such user" would surface as **503 "a dependent service is unreachable"** —
wrong status, and actively misleading. Adding
`@ExceptionHandler(HttpClientErrorException.NotFound.class)` would fix the status
but not the real problem: **`ProductLookup` and `UserLookup` throw the identical
exception type**, so a global handler cannot tell a missing product from a missing
user. The fallback is the only place that still knows which service the call went
to. That is what earns it its keep.

`ignore-exceptions` is a statistics filter. It has no opinion about what the API
returns.

### 18.5 `ignore-exceptions` vs `record-exceptions`

There is no `count-exceptions` property. The pair on `CircuitBreakerConfig` is:

```java
private Class<? extends Throwable>[] recordExceptions;   // YAML: record-exceptions
private Class<? extends Throwable>[] ignoreExceptions;   // YAML: ignore-exceptions
```

`record-exceptions` is a **whitelist**, and the three-way branch above shows its
trap: once it is set, anything not listed and not ignored falls to the `else` and
is counted as a **success**. It is not skipped.

```yaml
record-exceptions:
  - java.io.IOException      # a 500 from user-service would now count as a SUCCESS
```

`ignore-exceptions` is subtractive and safe: everything counts except what you
name. For the requirement here — "count everything except 404" —
`ignore-exceptions` alone is correct, and adding `record-exceptions` would only
punch holes in the coverage.

Two more rules:

- Matching is by **assignability**, so listing `HttpClientErrorException$NotFound`
  covers 404 only. A 400 or a 409 still counts as a failure.
- **Ignore is tested before record**, so a type appearing in both is ignored.

### 18.6 The TimeLimiter is gone — slow calls replace it

`CircuitBreakerFactory.run()` used to wrap every call in a `TimeLimiter`
defaulting to `Duration.ofSeconds(1)`, executed on a separate `ExecutorService`
thread. **`@CircuitBreaker` applies none.** resilience4j has a separate
`@TimeLimiter` annotation, but it requires the method to return a
`CompletionStage`, which would ripple through `CartService` for no real gain.

The annotation-era replacement is the pair in the YAML:

```yaml
slow-call-duration-threshold: 4s
slow-call-rate-threshold: 50
```

A call slower than 4s is **recorded as a failure** and can open the breaker.
**It records; it does not abort** — the caller still waits for the response. That
is the honest trade-off versus the old TimeLimiter, which really did cut the call
off at 4s.

Losing the executor is otherwise a simplification: the call now runs on the
request thread, so `@Transactional` context, MDC and the Zipkin trace context all
propagate normally. The old setup needed
`spring.cloud.circuitbreaker.resilience4j.disable-thread-pool: true` as an escape
hatch for exactly that. Actual request timeouts, if wanted, belong on the
`RestClient` request factory instead — a much better place for them.

### 18.7 Fallbacks fail fast — deliberately

Each lookup declares **two** fallbacks with the same name and a different final
parameter:

```java
private ProductResponse getProductFallback(Long productId, HttpClientErrorException.NotFound ex) {
    throw new ProductNotFoundException("Product with id " + productId + " does not exist");
}

private ProductResponse getProductFallback(Long productId, Throwable throwable) {
    logger.warn("product-service call failed for productId={}: {}", productId, throwable.toString());
    throw new ServiceUnavailableException("product-service", throwable);
}
```

resilience4j builds a map keyed by throwable type and walks the thrown
exception's superclass chain (`getSuperclass` + `isAssignableFrom`), picking the
most specific match. That pair replaces the `instanceof` check the old
`CircuitBreakerFactory` lambda needed.

`private` is fine. Fallbacks are discovered with `ReflectionUtils.doWithMethods`
and then `makeAccessible`-ed — they are never looked up as public methods.

Both throw rather than returning degraded data. `ServiceUnavailableException` is
mapped to **503 `DOWNSTREAM_UNAVAILABLE`** in `GlobalExceptionHandler`, which also
closes a real gap: a breaker in the OPEN state throws `CallNotPermittedException`,
neither of which any pre-existing handler matched — it would have surfaced as a
500.

Returning something plausible would be actively wrong on these paths. If
`ensureUserExists` degraded to "the user is fine", `createOrder` would write
orders for users it could not validate. **The value of a circuit breaker here is
fast failure and not hammering a dead service, not fake success.** A read-only
path such as `fetchUserCart` could reasonably degrade to an empty cart instead;
`fetchCartItems`, which feeds order creation, must not.

### 18.8 Placement, and why `OrderService` has no breaker

| call site | breaker | wraps |
|---|---|---|
| `ProductLookup.getProduct` | `product-service` | the product lookup only |
| `UserLookup.getUser` | `user-service` | the user lookup only |
| `CartService` | none — it calls the two beans above | — |
| `OrderService.createOrder` | none | — |

`createOrder` makes no direct downstream call. Its only remote dependency is
transitive — `createOrder → cartService.fetchCartItems → ensureUserExists →
userLookup.getUser` — and that is already guarded, so it inherits the protection.
Adding a breaker there would nest two around the same call: the inner one opens
first, and the outer would then count the resulting `ServiceUnavailableException`
as its own failure and trip a second breaker for no benefit.

With user-service down, `createOrder` returns 503 and **no order is written** —
the correct outcome.

**Transaction boundary.** `OrderService` originally carried no `@Transactional`,
so `orderRepository.save(order)` and `cartService.clearCart(userId)` committed in
separate transactions and a failure between them left the order persisted with the
cart still full. A class-level `@Transactional` (`jakarta.transaction`, matching
`CartService`) was added. Default propagation is `REQUIRED`, so `fetchCartItems`,
the repository `save` and `clearCart` all join the one transaction `createOrder`
opens; a failure in any of them rolls back the order. All the exceptions in play —
`ServiceUnavailableException`, `UserNotFoundException`, `ProductNotFoundException`
— are unchecked, which is what jakarta's default `rollbackOn` triggers on.

The trade-off is that the transaction spans a **remote call**, with a database
connection held open across it. This got slightly worse when the TimeLimiter went
away: nothing now caps how long that connection can be pinned, where previously it
was 4s. The circuit breaker still cuts against it — once the `user-service`
breaker is OPEN, calls fail in microseconds instead of waiting on a dead socket,
so a sick dependency stops draining the connection pool — but a **connect/read
timeout on the `RestClient` request factory** is the proper fix and is not yet
configured. The stricter alternative, validating the user *outside* the
transaction, needs a separate bean or a `TransactionTemplate`, since calling a
`@Transactional` method from within the same class bypasses the proxy — the same
rule as §18.2, in a different guise.

### 18.9 Observing it

Both endpoints need **no configuration**. Unlike the gateway's routes endpoint
(§15), they are declared `@Endpoint(id="circuitbreakers")` and
`@Endpoint(id="circuitbreakerevents")` with no `enableByDefault=false`, so the
`include: "*"` already in the shared `application.yml` is enough.

They live on port 9090. `order-service` publishes it as
`127.0.0.1:9083:9090` in `docker-compose.yml`, so they open in a browser on
this machine directly:

```
http://localhost:9083/actuator/circuitbreakers
http://localhost:9083/actuator/circuitbreakerevents
http://localhost:9083/actuator/streamcircuitbreakerevents    ← Server-Sent Events, live
http://localhost:9083/actuator/health                        ← now includes breaker state
```

That is a deliberate, scoped relaxation of §16 — cloud-gateway has the same
arrangement on 7073 (§19.10). The `127.0.0.1` prefix matters: without it Docker
binds `0.0.0.0` and the whole LAN can fetch `/actuator/heapdump`, which is the
JVM heap with every credential in it. `/actuator/shutdown` is *not* part of the
cost, for a reason that turned out to be a bug rather than a design choice — see
§12. The remaining services stay unpublished and are still reached with
`docker exec ecom_gateway curl -s http://<service>:9090/actuator/...`.

**Breakers now exist at startup** — a behaviour change from the factory approach.
`CircuitBreakerConfiguration.initCircuitBreakerRegistry` iterates the declared
`instances:` map and calls `registry.circuitBreaker(name, config)` for each, so
both appear in `CLOSED` before any traffic arrives. Under the old factory they
were created lazily on first `create(id).run(...)`, which meant
`{"circuitBreakers":{}}` right after a restart — correct, but confusing enough to
look like a bug.

`bufferedCalls` is the field to watch while testing: **if it is not increasing,
your exceptions are being ignored** (§18.4) and no threshold will ever be reached.
`failureRate: "-1.0%"` means "not enough calls yet" — below
`minimum-number-of-calls`, no rate is computed.

To exercise it: `docker compose stop product-service`, then POST to `/api/carts`
(with the `X-User-ID` header — see §8; the userId is a **header**, not a path
segment) six times. Expect 503s, then `"state": "OPEN"` for `product-service`
while `user-service` stays `CLOSED` — proof the two breakers are independent.
Roughly 10s later it moves to `HALF_OPEN` unattended.

**Driving HALF_OPEN back to OPEN needs real failures, not 404s.** Because
`ignore-exceptions` covers `HttpClientErrorException$NotFound`, a 404 is recorded
as *neither* success nor failure. HALF_OPEN waits for
`permitted-number-of-calls-in-half-open-state` (3) calls to be **recorded** before
it re-evaluates, and `max-wait-duration-in-half-open-state` defaults to 0 meaning
"wait indefinitely". So hammering a nonexistent product id leaves the breaker
stuck in HALF_OPEN forever. Stop the container instead — a transport failure is
recorded. Calls beyond the permitted 3 are rejected with
`CallNotPermittedException`, which is likewise not counted.

`resilience4j_circuitbreaker_state` is also exported to `/actuator/prometheus` via
`resilience4j-micrometer`, and Prometheus already scrapes `order-service:9090`
after the §16 target fix, so a Grafana panel needs no new plumbing. Other useful
series: `resilience4j_circuitbreaker_failure_rate`, `_buffered_calls`, `_calls`,
`_not_permitted_calls`.

### 18.10 The previous approach — `CircuitBreakerFactory`, and the registry trap

Kept because the trap is not specific to the factory: **it breaks the annotation
approach in exactly the same way.**

The first version of `CustomCircuitBreakerConfig` declared a
`CircuitBreakerRegistry` bean built with `CircuitBreakerRegistry.of(config)` —
which is what the official resilience4j docs show, and which is wrong under Spring
Boot. resilience4j's
`AbstractCircuitBreakerConfigurationOnMissingBean.circuitBreakerRegistry(...)` is
`@ConditionalOnMissingBean` and assembles the registry from three collaborators:

| collaborator | what is lost if you replace the registry |
|---|---|
| `CircuitBreakerConfigurationProperties` | all `resilience4j.*` YAML is ignored |
| `EventConsumerRegistry` | `/actuator/circuitbreakerevents` returns nothing |
| `CompositeCustomizer` | customizer beans stop applying |

With the current YAML-driven setup the first row is fatal: publish your own
registry and the entire §18.3 block is silently discarded, leaving both breakers
on resilience4j's defaults — `minimum-number-of-calls: 100`, so they never open.
The aspect keeps working throughout, because it receives the registry as a
constructor argument and gets yours. That is what makes the mistake dangerous: it
looks fine.

If Java configuration is ever wanted again, the correct shape is a
`Customizer<Resilience4JCircuitBreakerFactory>` bean, which *adds to* the
auto-configured registry rather than replacing it.
`CustomCircuitBreakerConfig.java` still holds that version with `@Configuration`
and `@Bean` commented out; nothing references it any more and it can be deleted.

The factory API also had one genuinely confusing property worth recording:
`run(supplier, fallback)` catches **every** throwable and invokes the fallback,
including exceptions the breaker was told to ignore. The annotation behaves the
same way (§18.4), so this is not a reason to prefer either — but it surprises
people in both.

### Verification status

`mvn -DskipTests compile` passes on order-service with the new
`ProductLookup`/`UserLookup` beans and the `spring-boot-starter-aspectj`
dependency. The **annotation/YAML setup has not yet been exercised at runtime** —
in particular the `ignore-exceptions` string → `Class[]` binding and the aspect
activation are reasoned from the bytecode, not observed. First run should confirm
that `/actuator/circuitbreakers` lists both instances at startup with
`failureRateThreshold: "50.0%"` (proving the YAML bound, not the defaults) and
that `bufferedCalls` increments on traffic (proving the aspect is live).

The preceding `CircuitBreakerFactory` version *was* confirmed at runtime:
order-service started both connectors (Tomcat on 8083 for the API, 9090 for
actuator), registered as `ORDER-SERVICE` in Eureka, and a single
`GET /api/carts` materialised the `user-service` breaker in `CLOSED` with the
configured 50% threshold. Its OPEN/HALF_OPEN transitions were never driven end to
end either.

Every API, default and dependency fact quoted above was verified against the
resolved POMs and compiled bytecode in `~/.m2` (spring-cloud-circuitbreaker-
resilience4j 5.0.2, spring-cloud-commons 5.0.2, resilience4j 2.3.0,
spring-boot-dependencies 4.1.0), not from memory.

---

## 19. Circuit breakers in cloud-gateway — route filters + YAML

The project now runs **two different circuit-breaker styles**, deliberately:
order-service uses the `@CircuitBreaker` annotation on its own beans (§18), and
cloud-gateway wraps whole *routes* in a gateway filter. They share the
resilience4j core and the same actuator endpoints, but almost nothing else —
different dependency, different registration timing, different failure
definition, and one applies a time limiter while the other does not. §19.9 is
the side-by-side.

The gateway version was written first and did not work: with product-service
stopped, neither `/actuator/health` nor `/actuator/circuitbreakers` showed any
sign of a breaker. The cause and the four other problems found alongside it are
below.

### 19.1 The dependency

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
</dependency>
```

The **reactor** variant, not the plain
`spring-cloud-starter-circuitbreaker-resilience4j` order-service uses. The
gateway is WebFlux, and `SpringCloudCircuitBreakerResilience4JFilterFactory`
takes a `ReactiveCircuitBreakerFactory` in its constructor; the servlet starter
never publishes one. What the reactor starter adds on top of the shared core is
`resilience4j-reactor`, which supplies `CircuitBreakerOperator` — the Reactor
operator the filter actually composes into the chain.

No version element: `spring-cloud-dependencies` 2025.1.2 imports
`spring-cloud-circuitbreaker-dependencies` 5.0.2, which manages it. It was
originally declared with an explicit `<version>5.0.2</version>` and
`<scope>compile</scope>` — the same values the BOM supplies, so both were
removed as noise.

**No `spring-boot-starter-aspectj`, unlike order-service.** That was the first
question asked, and the answer is no. §18.1 explains why order-service needs it:
resilience4j hides its `CircuitBreakerAspect` bean behind
`@Conditional(AspectJOnClasspathCondition)`, so without AspectJ on the classpath
every `@CircuitBreaker` annotation is silently ignored. The gateway has no
annotations. `GatewayConfig` calls the programmatic `ReactiveCircuitBreaker` API
from inside a route filter — no aspect, no proxy, no self-invocation trap. The
rule generalises: the AspectJ starter is needed by the *annotation*, not by
resilience4j.

The actuator endpoints (`/actuator/circuitbreakers`,
`/actuator/circuitbreakerevents`) and the `resilience4j.*` property binding are
not in the starter's own dependency list either. They arrive transitively:
`spring-cloud-circuitbreaker-resilience4j` → `resilience4j-spring-boot3`
(compile scope), whose `CircuitBreakerAutoConfiguration` contributes both.

### 19.2 Where the breaker is applied

`gateway/src/main/java/com/ramesh/gateway/config/GatewayConfig.java`:

```java
private final static String PRODUCT_CB = "productServiceCB";
private final static String USER_CB    = "userServiceCB";
private final static String ORDER_CB   = "orderServiceCB";

.route("PRODUCT-SERVICE", r -> r
        .path("/api/products/**")
        .filters(f -> f.circuitBreaker(config -> config
                .setName(PRODUCT_CB)
                .setFallbackUri("forward:/fallback/products")))
        .uri("lb://product-service"))
```

`.circuitBreaker(...)` resolves to `SpringCloudCircuitBreakerFilterFactory`,
whose `apply(Config)` does, in order: enable request-body caching if a fallback
URI is set (a forward has to replay the body), call
`reactiveCircuitBreakerFactory.create(config.getId())`, translate the configured
status codes, and return the filter.

**Every route originally shared one name, `"ecomCircuitBreaker"`.** The
consequence is visible in `Config.getId()`:

```
if (!hasText(name) && hasText(routeId)) return routeId;
return name;
```

The name wins whenever it is set, and the factory looks the breaker up in the
shared registry by that id — so all three routes resolved to a **single**
`CircuitBreaker` object. Failures from a stopped product-service would have
opened the breaker for `/api/users/**` and `/api/orders/**` as well, and the
user would have seen two services fail because a third was down. Split into one
name per downstream service.

Leaving `setName` off entirely is the other valid option: `getId()` then falls
back to the route id, which is already unique (`PRODUCT-SERVICE`,
`USER-SERVICE`, `ORDER-SERVICE`). Explicit names were kept because they have to
match the YAML keys anyway, and route ids are uppercase-hyphenated, which reads
badly as a property key.

**Where the filter sits relative to the load balancer** matters for the test
being run. Route filters declared this way get a low order and therefore run
*before* the global filters — `RouteToRequestUrlFilter` (10000),
`ReactiveLoadBalancerClientFilter` (10150), `NettyRoutingFilter`
(`LOWEST_PRECEDENCE`). The breaker wraps all of them, so "no instances
available" from the load balancer is inside the breaker and counts as a failure,
exactly like a connection refused or a timeout would.

### 19.3 The configuration — and the bug that made all of it disappear

`configserver/src/main/resources/config/cloud-gateway.yml` originally had:

```yaml
management:
  endpoint:
    gateway:
      access: read-only
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true

  #Circuit Breaker
  resilience4j:            # <-- two spaces. management.resilience4j.*
    circuitbreaker:
      instances:
        ecomCircuitBreaker:
          minimum-number-of-calls: 5
          ...
```

resilience4j binds from **`resilience4j.circuitbreaker.*`** at the document
root. Indented under `management:` the whole block became
`management.resilience4j.*`, which nothing binds to. Boot does not fail on
unrecognised properties, so the application started, the actuator answered, and
the entire configuration was discarded in silence.

What was left was `CircuitBreakerConfig.ofDefaults()`, and its two relevant
defaults explain both symptoms exactly:

| default | symptom it produced |
|---|---|
| `minimumNumberOfCalls = 100` | the failure rate is not even computed below 100 calls in the window, so the breaker could never open during a hand-run test |
| `registerHealthIndicator = false` | nothing in `/actuator/health`, regardless of the (correctly placed) `management.health.circuitbreakers.enabled: true` |

Those two properties sit next to each other in `management:` in this file, one
valid and one not, which is what made the mistake easy to miss: the health
switch was in the right place and had no visible effect, because the thing it
switches on was never asked for.

Moved to the root, with a `configs.default` block the instances inherit — same
shape as order-service (§18.3):

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 50
        register-health-indicator: true
    instances:
      productServiceCB: { base-config: default }
      userServiceCB:    { base-config: default }
      orderServiceCB:   { base-config: default }
```

The instance keys must equal the strings passed to `.setName(...)`. A mismatch
is **not** a startup error — resilience4j falls back to `configs.default` — so a
typo surfaces as a breaker running on the wrong settings, never as a failure.
The same trap as §18.3.

The `ignore-exceptions` entry copied over from order-service was **removed**;
§19.7 explains why it could never have fired here.

Because config-server bind-mounts `./configserver/src/main/resources/config`,
this file is live on disk — but see §19.10 on why a gateway restart is still
needed.

### 19.4 Why `/actuator/circuitbreakers` was empty even before the config was fixed

Two independent reasons, and only one of them was the YAML.

`ReactiveResilience4JCircuitBreakerFactory.create(id)` does **not** touch the
registry. Its bytecode reads the configuration out of the registry
(`getConfiguration(id)`), reads a `TimeLimiterConfig`, and constructs a
`ReactiveResilience4JCircuitBreaker` wrapper. The actual registration —

```
circuitBreakerRegistry.circuitBreaker(id, config, tags)
```

— is inside `ReactiveResilience4JCircuitBreaker.run()`, so a route-filter
breaker materialises only when the **first request traverses that route**.
Before any traffic the endpoint is legitimately empty, and that is not a
misconfiguration.

Declaring the instances in YAML fixes this as a side effect.
`resilience4j-spring-boot3`'s `initCircuitBreakerRegistry` iterates
`getInstances()` at startup and calls `registry.circuitBreaker(name, config)`
for each, so all three now appear `CLOSED` before anything is sent. The order
also happens to be the one that matters: `CircuitBreakerRegistry.circuitBreaker`
is a `computeIfAbsent`, so the eager instance built from YAML is the one the
lazy call in `run()` finds and returns. Without a declaration, `run()` creates
the breaker from `Resilience4JConfigurationProperties` defaults instead — which
is the state the gateway had been in.

This is the same eager-vs-lazy distinction noted in §18.9, arrived at from the
opposite direction: order-service gained eager creation by moving *to*
annotations plus YAML, the gateway gained it by fixing the YAML.

### 19.5 The reactive path applies a TimeLimiter — the annotation path does not

This is the sharpest difference between the two styles, and it was a bug waiting
to fire. `ReactiveResilience4JCircuitBreaker.run()` composes:

```
.transform(CircuitBreakerOperator.of(circuitBreaker))
.timeout(timeLimiter.getTimeLimiterConfig().getTimeoutDuration())
```

and on expiry calls `circuitBreaker.onError(duration, MILLISECONDS, throwable)`
— a recorded failure plus a fallback response. **The default timeout is one
second.** A cold downstream JVM clears that on its first request without
difficulty, so the gateway would have opened breakers against services that were
perfectly healthy, and the 3s/4s slow-call thresholds would never have been
reached because the timeout aborts the call long before.

§18.6 records the mirror-image fact for order-service: `@CircuitBreaker` applies
**no** time limiter at all, because `@TimeLimiter` would require a
`CompletionStage` return type. So the same project has one path that times out
aggressively by default and one that never times out — neither of which is
obvious from the configuration.

Made explicit, with instance names matching the circuit-breaker instance names:

```yaml
resilience4j:
  timelimiter:
    configs:
      default:
        timeout-duration: 5s
        cancel-running-future: true
    instances:
      productServiceCB: { base-config: default }
      userServiceCB:    { base-config: default }
      orderServiceCB:   { base-config: default }
```

`slow-call-duration-threshold` was lowered from 4s to **3s** at the same time so
it sits below the 5s timeout. The ordering is the point: a call slower than 3s
is *recorded* as slow while still being allowed to finish, and only a call
past 5s is *aborted*. With the threshold above the timeout, the slow-call
machinery is dead configuration — every slow call is killed and booked as a
plain failure first.

### 19.6 `register-health-indicator`, and what it does *not* do

Turning the health indicator on is what puts breaker state into
`/actuator/health` — the second thing that was being looked for and not found.
Two switches are needed, and both are now present: `register-health-indicator:
true` per instance, and `management.health.circuitbreakers.enabled: true`
(the latter is Boot's default anyway, but it is explicit here).

The obvious next worry is that this collides with the compose healthcheck.
cloud-gateway, unlike order-service, has one:

```yaml
healthcheck:
  test: [ "CMD", "curl", "-f", "http://localhost:9090/actuator/health" ]
```

`curl -f` exits non-zero on any 4xx/5xx, and actuator serves a `DOWN` aggregate
as **HTTP 503**. If an OPEN breaker turned the aggregate DOWN, Docker would mark
the gateway unhealthy for a *downstream* outage the gateway is handling
correctly — reporting it as broken precisely when it is doing its job.

**It does not, by default.** This was initially asserted the other way round
here and is wrong; the bytecode says otherwise. Two steps matter.

`CircuitBreakersHealthIndicator.mapBackendMonitorState` maps state to health:

| breaker state | health status |
|---|---|
| `CLOSED` | `UP` |
| `OPEN` | `DOWN` **only if** `allowHealthIndicatorToFail`, otherwise the custom status `CIRCUIT_OPEN` |
| `HALF_OPEN` | the custom status `CIRCUIT_HALF_OPEN` |
| anything else | `UNKNOWN` |

and `allowHealthIndicatorToFail(...)` reads
`resilience4j.circuitbreaker.instances.<name>.allow-health-indicator-to-fail`
with `.orElse(false)` — so by default OPEN produces `CIRCUIT_OPEN`, not `DOWN`.

Then Boot's `SimpleStatusAggregator.getAggregateStatus` does:

```
statuses.stream().filter(this::contains).min(comparator).orElse(Status.UNKNOWN)
```

The `filter` keeps only statuses present in the configured order list, which
defaults to `Status.DEFAULT_ORDER` = `DOWN`, `OUT_OF_SERVICE`, `UP`, `UNKNOWN`.
`CIRCUIT_OPEN` is in none of them, so it is **discarded before the min is
taken** — first inside the indicator itself (whose own status is the aggregate
of its per-breaker statuses), and again at the endpoint level. The other
contributors are `UP`, so the aggregate is `UP`, and
`SimpleHttpCodeStatusMapper` returns **200** for everything except `DOWN` and
`OUT_OF_SERVICE`.

Net effect with the settings in this project: an OPEN breaker shows up in
`/actuator/health` as a `circuitBreakers` entry with `state: OPEN` in its
details, while the endpoint still returns 200 and the healthcheck still passes.
`register-health-indicator` buys visibility, not a failing probe.

The switch that changes this is per-instance:

```yaml
allow-health-indicator-to-fail: true
```

Set that and OPEN becomes a genuine `DOWN`, which survives the aggregator's
filter, drives the aggregate DOWN, and returns 503. **Then** the collision with
`curl -f` is real. It is not set anywhere here.

The compose healthcheck was still repointed at the liveness probe:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
```

```yaml
test: [ "CMD", "curl", "-f", "http://localhost:9090/actuator/health/liveness" ]
```

To be clear about what that change is and is not: it fixes **no current bug**.
It is kept on two independent grounds. First, it is the more correct question
for a container healthcheck to ask — "is this JVM alive and able to serve?"
rather than "is every dependency of this JVM healthy?" A gateway whose
downstream is down is still a working gateway, and restarting it would not help.
Second, it makes `allow-health-indicator-to-fail: true` safe to turn on later,
which is otherwise a one-line change with a non-obvious blast radius.

`management.endpoint.health.probes.enabled` (present in `spring-boot-health`
4.1.0) adds the `liveness` and `readiness` groups outside Kubernetes. The
`liveness` group contains only `livenessState`, so no health *indicator* —
circuit breakers, database, disk space, RabbitMQ — can influence it.
`/actuator/health` keeps showing everything for humans; Docker asks the narrower
question. Note this also decouples the healthcheck from the datasource and
RabbitMQ indicators, which is a behaviour change worth knowing about: a gateway
that cannot reach RabbitMQ will now stay "healthy" to Docker.

Nothing in the compose file `depends_on` the gateway's health, so neither the
old nor the new arrangement affects startup ordering.

### 19.7 What counts as a failure at a gateway is not what counts in a client

`ignore-exceptions` was carried over verbatim from order-service:

```yaml
ignore-exceptions:
  - org.springframework.web.client.HttpClientErrorException$NotFound
```

It is dead configuration in the gateway, for a reason worth stating plainly: **a
reverse proxy does not get exceptions from downstream, it gets responses.**
`HttpClientErrorException` is a `RestClient`/`RestTemplate` type. The gateway
proxies over Netty via `NettyRoutingFilter` and never constructs one — the class
is on the classpath (spring-web comes with WebFlux) so the property binds
without error, and then matches nothing, forever.

The practical consequence is the opposite of what the copied line suggests. In
order-service a downstream 404 arrives as a thrown `NotFound` and *had* to be
excluded from the statistics (§18.4). At the gateway a downstream 404 — or 500,
or 503 — arrives as an ordinary HTTP response that the filter passes straight
through, and the breaker records it as a **SUCCESS**. Nothing needs excluding;
if anything, the risk runs the other way, and a downstream that is failing fast
with 500s will never trip the gateway breaker at all.

The lever for that is not an exception list. It is on the filter:

```java
config.setStatusCodes(Set.of("500", "503"))
```

which makes the filter throw `CircuitBreakerStatusCodeException` for those
responses, recording a failure and routing to the fallback. Not enabled here —
it changes what clients see (a downstream 500 becomes the gateway's 503 fallback
body), and the scenario being tested is a *stopped* service, which already fails
as an exception inside the breaker (§19.2). Recorded so the option is not
rediscovered as an exception-list problem.

So the gateway breaker currently trips on: connection refused, no instances
available from the load balancer, and calls exceeding the 5s time limit. Not on
downstream error statuses.

### 19.8 The fallback

`gateway/src/main/java/com/ramesh/gateway/controller/FallbackController.java`
is an ordinary `@RestController`, because `forward:` re-dispatches the exchange
into the gateway's own `DispatcherHandler` (which
`SpringCloudCircuitBreakerResilience4JFilterFactory` receives as an
`ObjectProvider` for exactly this purpose). Each method returns
`503 SERVICE_UNAVAILABLE` with a short message.

The three handlers were `@GetMapping`. **A forward preserves the original HTTP
method**, so a `POST /api/carts` that tripped the order breaker would forward to
`/fallback/orders` as a POST and come back **405 Method Not Allowed** instead of
the intended 503 — a confusing failure, since the breaker did fire and the
fallback did run. Changed to bare `@RequestMapping`, which matches every method.
A stray `org.w3c.dom.stylesheets.LinkStyle` import was removed at the same time.

Note that `apply()` enables request-body caching whenever a fallback URI is set;
that is what allows a POST body to survive being replayed into the forward.

### 19.9 Gateway vs order-service, side by side

| | order-service (§18) | cloud-gateway (§19) |
|---|---|---|
| how it is applied | `@CircuitBreaker` on a bean method | route filter, programmatic API |
| starter | `spring-cloud-starter-circuitbreaker-resilience4j` | `…-circuitbreaker-reactor-resilience4j` |
| needs `spring-boot-starter-aspectj` | **yes** — the aspect is `@Conditional` on AspectJ | no |
| proxy / self-invocation trap | yes — forced the `ProductLookup`/`UserLookup` split | none |
| what a failure is | a thrown exception, filtered by `ignore-exceptions` | an exception from the proxy chain; downstream statuses count as success unless `setStatusCodes` |
| time limiter | **none** | **yes, default 1s** — set to 5s explicitly |
| registration | eager, from `instances:` | lazy at first request, unless `instances:` declares it |
| fallback | typed `fallbackMethod` overloads on the bean | `forward:` to a controller |
| granularity | one breaker per remote call | one breaker per route |

The two rows that caused real bugs are the time limiter and, in the gateway's
case, the fact that a proxy sees responses rather than exceptions. Configuration
copied between these two services will bind cleanly and behave differently.

### 19.10 Observing it

Rebuild and restart — Java and `pom.xml` both changed:

```
docker compose build cloud-gateway && docker compose up -d cloud-gateway
```

A restart is required even for the YAML alone. config-server serves the file
from a bind mount, so the *content* is live, but resilience4j builds its registry
during startup from the bound properties; `/actuator/busrefresh` will not
reconstruct it. This is the one place where the §18.3 note about pushing config
changes without a rebuild does not carry over.

Actuator is published on host **7073**, loopback-bound
(`ports: "127.0.0.1:7073:9090"`), so from the browser on this machine:

| URL | what it should show |
|---|---|
| `http://localhost:7073/actuator/circuitbreakers` | all three, `CLOSED`, **before any traffic** — proves the YAML bound and the instances were created eagerly (§19.4) |
| `http://localhost:7073/actuator/health` | a `circuitBreakers` entry — proves `register-health-indicator` took effect (§19.6) |
| `http://localhost:7073/actuator/health/liveness` | `UP`, and stays `UP` when a breaker opens — this is what Docker polls |
| `http://localhost:7073/actuator/circuitbreakerevents` | per-call `SUCCESS` / `ERROR` / `STATE_TRANSITION` records |
| `http://localhost:7073/actuator/gateway/routes` | the routes, each showing its `CircuitBreaker` filter |

If the three breakers are **absent** before traffic, the YAML is still not
binding — that is the single most useful diagnostic here, and it is what
distinguishes "misconfigured" from "not yet exercised".

To drive it:

1. `docker compose stop product-service`
2. `GET http://localhost:8080/api/products/1` six times. Calls 1–5 return the
   fallback 503 with `bufferedCalls` climbing; call 6 crosses
   `minimum-number-of-calls: 5` with a 100% failure rate and flips the state to
   `OPEN`.
3. `/api/users/1` should still be `CLOSED`. That is the per-service split from
   §19.2 doing its job, and it is the thing the shared name would have broken.
4. After `wait-duration-in-open-state: 10s`, the state moves to `HALF_OPEN` on
   its own, without traffic, because
   `automatic-transition-from-open-to-half-open-enabled` is set.

One caveat on step 2: Eureka's client-side registry cache means the gateway can
keep the stale product-service instance for up to ~30s after it stops (§17). The
early failures will therefore be connection-refused rather than "no instances
available". Both are exceptions inside the breaker and both count as failures,
so the test works either way — the error text just changes partway through.

### Verification status

`mvn -DskipTests compile` passes on the gateway. **Nothing here has been run at
runtime** — no containers were up during this work.

What *is* verified, against the resolved POMs and compiled bytecode in `~/.m2`
(spring-cloud-gateway-server-webflux 5.0.0, spring-cloud-circuitbreaker-
resilience4j 5.0.2, resilience4j 2.3.0, spring-boot-health 4.1.0):

- `Config.getId()` returns the name when one is set (§19.2) — read from
  bytecode, and the basis for the shared-breaker diagnosis
- `create()` does not register; `run()` does (§19.4) — read from bytecode
- `run()` applies `Mono.timeout(...)` from the `TimeLimiterConfig` and reports
  the timeout via `onError` (§19.5) — read from bytecode
- `spring-cloud-starter-circuitbreaker-reactor-resilience4j` does not pull
  AspectJ, and `spring-cloud-circuitbreaker-resilience4j` pulls
  `resilience4j-spring-boot3` at compile scope (§19.1) — read from the POMs
- `management.endpoint.health.probes.enabled` exists in Boot 4.1.0 (§19.6) —
  read from `spring-configuration-metadata.json`
- the starter is BOM-managed at 5.0.2 (§19.1) — read from
  `spring-cloud-dependencies-2025.1.2.pom`

What is **not** verified: that the corrected YAML actually binds, that the three
breakers appear eagerly, that the health indicator registers, and that the
liveness probe stays `UP` while a breaker is OPEN. Those are the four checks in
the table above, and they are exactly the claims that reasoning from bytecode
cannot settle.

## 20. Rate limiting in cloud-gateway — `RedisRateLimiter`

Throttling at the edge, so a burst is rejected at the gateway instead of being
fanned out to a downstream service. Unlike the circuit breaker in §19 — which
reacts to a downstream that is *already* failing — this caps arrival rate before
anything downstream is touched.

### 20.1 What it is made of

| piece | where | role |
|---|---|---|
| `spring-boot-starter-data-redis-reactive` | `gateway/pom.xml` | supplies `ReactiveStringRedisTemplate`, which the limiter needs |
| `spring.data.redis.host` | `config/cloud-gateway.yml` | points at the `redis-server` container |
| `RedisRateLimiter` bean | `GatewayConfig` | the token-bucket algorithm and its limits |
| `KeyResolver` bean | `GatewayConfig` | decides what a "client" is — one bucket per resolved key |
| `.requestRateLimiter(...)` | `GatewayConfig`, per route | actually attaches the limiter to traffic |
| `redis-server` | `docker-compose.yml` | where the buckets live |

The state is not in the gateway. It is in Redis, as a pair of keys per bucket,
which is what makes the limit hold across multiple gateway instances rather than
becoming *N* × the configured rate.

### 20.2 It failed silently three times, for three different reasons

Worth recording together, because all three produced the same symptom — every
request returning `200`, no errors visible to the client — from unrelated causes.

**One: no `spring.data.redis.host`.** The container was added to compose, but
nothing told the gateway where it was. The default is `localhost`, which inside
the gateway container is the gateway. The log had it:

```
Caused by: io.lettuce.core.RedisConnectionException: Unable to connect to localhost/<unresolved>:6379
ERROR ... o.s.c.g.f.ratelimit.RedisRateLimiter : Error calling rate limiter lua
```

**Why that error did not surface as a 5xx** is the part worth internalising.
`RedisRateLimiter.isAllowed` puts an `onErrorResume` on the Lua call:

```java
log.error("Error calling rate limiter lua", t);
return Flux.just(Arrays.asList(1L, -1L));    // 1 == allowed
```

The limiter **fails open**. An unreachable Redis is indistinguishable, from the
client's side, from no rate limiter at all. That is a deliberate availability
choice — a Redis outage should not take the whole gateway down — but it means
Redis problems are only ever visible in the log and in `X-RateLimit-Remaining:
-1`. Grep for `Error calling rate limiter lua` whenever limiting stops working.

**Two: the compose volume.** `redis_data:/data/redis_db` persisted nothing —
redis writes `dump.rdb` to `/data`, its WORKDIR — so the mount only produced an
empty root-owned directory. The revision to `redis_data:/data/dump.rdb` would
have been worse than useless: a named volume always mounts as a *directory*, so
`dump.rdb` becomes a directory, the snapshot rename fails with `EISDIR`, and
because the image ships `stop-writes-on-bgsave-error yes`, Redis then refuses
**all writes** with MISCONF. The Lua script errors, `onErrorResume` fires, and
the limiter fails open again — the same symptom as cause one, from the opposite
end. Resolved by dropping persistence entirely (§20.6).

**Three: the filter was attached to one route.** Covered in §20.4. This is the
one that survived the first two fixes.

### 20.3 The `KeyResolver` — the key is the bucket

```java
@Bean
public KeyResolver hostNameKeyResolver(){
    return exchange -> {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return Mono.just(remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress());
    };
}
```

Three decisions in a small method.

**`getHostAddress()`, not `getHostName()`.** The original used `getHostName()`,
which on an unresolved `InetSocketAddress` performs a **reverse DNS lookup** —
blocking I/O. In the gateway that runs on a `reactor-http-epoll` thread, so
under load every request would park an event-loop thread waiting on PTR records
that a Docker bridge address does not have. `getHostAddress()` formats bytes
already in memory and performs no I/O. This never showed up as a bug only
because Redis was down and the filter was never reached on the tested route.

**A fallback key rather than `Mono.empty()`.**
`RequestRateLimiterGatewayFilterFactory` defaults `denyEmptyKey = true` with
`emptyKeyStatusCode = FORBIDDEN`, so an empty key means the request is rejected
with **403** and never rate-limited at all. Returning `"unknown"` keeps such
requests inside the limiter. The trade-off is that they share one bucket.

**What this actually buckets by, in Docker.** Traffic from the host arrives over
the bridge, so every client shares one source address:

```
request_rate_limiter.{PRODUCT-SERVICE.172.18.0.1}.tokens
```

`172.18.0.1` is the bridge, not the caller. For a load test that is fine — and
arguably what you want, since the whole JMeter run is then held to one rate —
but it is **not** per-user limiting. For that, key on an authenticated principal
or an API-key header. Note also that behind a real proxy the remote address is
the proxy's; `XForwardedRemoteAddressResolver` exists for that case and should
be used with an explicit trusted-hop count, never with the raw header.

### 20.4 Attaching it is per route — there is no global switch

Declaring the two beans makes them *available*. It attaches them to nothing. A
route without `.requestRateLimiter(...)` forwards everything:

```java
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
```

The buckets do not collide across routes even though all routes share one
`RedisRateLimiter` bean and one `KeyResolver`: the filter calls
`isAllowed(route.getId(), key)`, so the route id is part of the Redis key —
`{PRODUCT-SERVICE.172.18.0.1}` and `{USER-SERVICE.172.18.0.1}` are separate
buckets. Currently attached to `PRODUCT-SERVICE` and `USER-SERVICE` only;
`ORDER-SERVICE` and the two Eureka routes are unlimited.

**Order relative to the circuit breaker.** The rate limiter is declared first,
and declaration order is execution order — for a slightly subtle reason.
`GatewayFilterSpec.filter(GatewayFilter)` checks `instanceof Ordered` and, for
anything that is not, delegates to `filter(f, 0)`. Neither of these implements
`Ordered` (the breaker is an anonymous
`SpringCloudCircuitBreakerFilterFactory$1 implements GatewayFilter`; the rate
limiter is a lambda), so **both get order 0**.
`FilteringWebHandler.getAllFilters` then does:

```java
List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
combined.addAll(route.getFilters());
AnnotationAwareOrderComparator.sort(combined);
```

`List.sort` is stable, so equal orders keep insertion order. (This differs from
YAML routes, where `RouteDefinitionRouteLocator` assigns each filter an
incrementing order explicitly.)

Why this order matters is a metrics argument, not a correctness one — the
downstream is protected either way, since both filters sit before
`NettyRoutingFilter`:

- **limiter first** — a throttled request returns 429 without entering
  `cb.run(...)`; the breaker's sliding window contains only real downstream calls
- **breaker first** — the limiter runs *inside* the breaker, and a 429 completes
  the `Mono` normally, so resilience4j records it as a **SUCCESS**.
  Self-inflicted throttling then pads the window with calls that never left the
  gateway, diluting the failure rate meant to detect a sick downstream.

One sharp edge in the second arrangement: adding `.setStatusCodes("429")` to the
breaker (§19.7) would make the gateway's own throttling count as *failures* and
trip the breaker on itself.

### 20.5 `default-filters` — the global alternative, and why it does not apply here

The obvious way to avoid repeating the filter on every route:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          default-filters:
            - name: RequestRateLimiter
              args:
                rate-limiter: "#{@redisRateLimiter}"
                key-resolver: "#{@hostNameKeyResolver}"
```

The property is real — `spring.cloud.gateway.server.webflux.default-filters`, of
type `List<FilterDefinition>` — and `#{@beanName}` is resolved as SpEL against
the bean factory, which is how a YAML filter reaches a Java bean.

**It would have no effect on this project as it stands.** Default filters are
applied by `RouteDefinitionRouteLocator`, which builds routes from
`RouteDefinition` objects — YAML routes, the discovery locator, route definition
repositories. Every route here is built by the fluent Java API in
`GatewayConfig`, and `RouteLocatorBuilder.Builder` has exactly two fields
(`routes`, `context`); its `build()` is:

```java
Flux.fromIterable(this.routes).map(Buildable::build)
```

No `GatewayProperties`, no `getDefaultFilters()`. The two locators are
independent sources of `Route` objects, and only one of them consults the
default-filter list.

So this is a genuine fork, not a shortcut:

| | fluent Java (current) | YAML + `default-filters` |
|---|---|---|
| filter applies to | only routes that declare it | every `RouteDefinition` route |
| bean wiring | direct method call, compile-checked | `#{@name}` SpEL, fails at runtime on a typo |
| per-route overrides | natural | needs a route-level filter to override |
| would also cover | — | the two Eureka routes, and any discovery-locator routes |

That last row is the reason to think before switching: `default-filters` is
genuinely global, so the Eureka dashboard passthrough would start being
throttled at 1 req/s too — and a dashboard page pulls several static assets.
Adopting it would mean moving the routes back to YAML (§19.2 moved them out) and
re-tuning the limits, which is why the per-route form is kept for now.

### 20.6 The Redis container

```yaml
  redis-server:
    image: redis:8
    ports:
      - "127.0.0.1:6379:6379"
    command: ["redis-server", "--save", "", "--appendonly", "no"]
```

**Loopback-bound**, for the same reason as 7073 and 9093 (§16.2, §18.9). This
Redis has no `requirepass`, and an open unauthenticated Redis is more than a
data leak: `CONFIG SET dir` plus `CONFIG SET dbfilename` is an arbitrary file
write as the redis user. Plain `"6379:6379"` binds `0.0.0.0` and offers that to
the LAN. The gateway does not need the mapping at all — it reaches
`redis-server:6379` over `ecom-network` — so it exists purely for `redis-cli`
and RedisInsight from the host.

**Persistence off, deliberately.** The only thing stored is token buckets with
TTLs — per-second state that *should* be empty after a restart. Leaving the
image defaults (`save 3600 1 300 100 60 10000`, `stop-writes-on-bgsave-error
yes`) keeps a failure mode with no compensating benefit: a failed snapshot puts
Redis into MISCONF, and MISCONF means the limiter fails open (§20.2). Turning
persistence off removes the failure mode rather than leaving it to be monitored.
No volume is declared, and this image declares no `VOLUME` of its own
(`Config.Volumes` is `null`), so nothing is written and no anonymous volume
accumulates.

`depends_on: redis-server: condition: service_healthy` is set on the gateway,
but it buys less than it appears to: the Lettuce connection is lazy, so a
gateway that started before Redis would not fail — it would just fail open on
early requests.

### 20.7 Observing it

The constructor is `RedisRateLimiter(replenishRate, burstCapacity,
requestedTokens)`. It was first set to `(1, 1, 1)` — one request per second per
key with no burst allowance at all — deliberately strict, so the effect was
immediate rather than statistical while the limiter was being debugged. It now
reads `(10, 20, 1)`: ten per second sustained, twenty in a burst.

Send more than `burstCapacity` at once and the excess is rejected. At `(10, 20,
1)`, forty concurrent requests gave thirty `200` and ten `429` — twenty from the
bucket plus roughly ten replenished over the second the run took:

```
for i in $(seq 1 40); do curl -s -o /dev/null -w "%{http_code} " \
  http://localhost:8080/api/products & done; wait
```

Because replenishment is time-based, the split moves with how long the burst
takes to issue. `docker exec ecom_redis redis-cli flushall` between runs makes
consecutive tests comparable.

The limiter sets these on every response, which is the fastest way to tell a
working limiter from a failing-open one:

| header | meaning |
|---|---|
| `X-RateLimit-Remaining` | tokens left; **`-1` means the fail-open path ran** — Redis is unreachable |
| `X-RateLimit-Replenish-Rate` | tokens added per second |
| `X-RateLimit-Burst-Capacity` | bucket size |
| `X-RateLimit-Requested-Tokens` | cost of this request |

And from Redis directly:

```
docker exec ecom_redis redis-cli keys "request_rate_limiter*"
```

Keys appearing there is proof the Lua script actually executed. An empty
`dbsize` after a load test means the limiter never ran at all — either the route
has no `.requestRateLimiter(...)` (§20.4) or the connection is failing (§20.2).
Those two look identical from the client and are distinguished only here, and in
`/actuator/gateway/routes`, which lists each route's filters by name.

### Verification status

Unlike §19, this section **was** exercised at runtime.

Verified by running it:

- 10 concurrent requests to `/api/products` → nine `429`, one `200`
- `request_rate_limiter.{PRODUCT-SERVICE.172.18.0.1}.tokens` and `.timestamp`
  present in Redis afterwards; `dbsize` was `0` before
- the same test against `/api/users` produced `429`s while `/api/products` did
  not, which is what isolated §20.2's third cause
- `Error calling rate limiter lua` with `Connection refused:
  localhost/127.0.0.1:6379` found in `logs/cloud-gateway/cloud-gateway.log` —
  cause one, confirmed rather than inferred
- the running container reported `requirepass` empty, `save 3600 1 300 100 60
  10000`, `stop-writes-on-bgsave-error yes`, and `0.0.0.0:6379->6379/tcp`

Verified against compiled bytecode in `~/.m2` (spring-cloud-gateway-server-webflux
5.0.0):

- `isAllowed`'s `onErrorResume` returns `[1, -1]`, i.e. allowed (§20.2)
- `denyEmptyKey` defaults true and `emptyKeyStatusCode` to `FORBIDDEN`; the
  `Config` default status is `TOO_MANY_REQUESTS` (§20.3)
- `GatewayFilterSpec.filter` assigns order 0 to non-`Ordered` filters, and
  `getAllFilters` sorts with `AnnotationAwareOrderComparator` (§20.4)
- `RouteLocatorBuilder.Builder` never consults `getDefaultFilters()` (§20.5)
- `GatewayRedisAutoConfiguration.redisRateLimiter` is `@ConditionalOnMissingBean`,
  so the bean in `GatewayConfig` replaces it without a name clash

The `X-RateLimit-*` headers were confirmed later, after the limits were relaxed
to `new RedisRateLimiter(10, 20, 1)`: a request at the limit returned
`X-RateLimit-Remaining: 0`, `X-RateLimit-Burst-Capacity: 20`,
`X-RateLimit-Replenish-Rate: 10`, `X-RateLimit-Requested-Tokens: 1` — the
`Remaining: -1` fail-open value did not appear. 40 concurrent requests against
those limits gave thirty `200` and ten `429`, the extra ten over the burst
capacity being tokens replenished during the run.

Not verified: behaviour with more than one gateway instance sharing the buckets.

---

## 21. Asynchronous messaging — RabbitMQ, order-service → notification-service

The first piece of genuinely *asynchronous* inter-service communication in the
project. Everything before it was synchronous request/response: the gateway
proxies to a service (§15), order-service calls product-service and user-service
over `RestClient` (§17). Here order-service publishes an event and forgets about
it, and notification-service picks it up on its own schedule.

RabbitMQ itself is not new — it has been in `docker-compose.yml` since §16 as the
Spring Cloud Bus transport. What is new is the project using it directly, as an
application message broker, over the same connection.

> **This is stage 1 of 3.** The same flow is rebuilt on Spring Cloud Stream with
> the Rabbit binder in §22, and on Stream with the Kafka binder in §23. This
> section is kept as written — native `RabbitTemplate` / `@RabbitListener` — so
> the three can be compared. Where §22 supersedes something here it says so.

### 21.1 The shape of it

| piece | where | value |
|---|---|---|
| exchange | declared in both `RabbitMQConfig`s | `order.exchange`, type **topic**, durable |
| queue | declared in both `RabbitMQConfig`s | `order.queue`, durable |
| routing key | `config/order-service.yml`, `config/notification-service.yml` | `order.tracking` |
| producer | `OrderService.createOrder` | `eventPublisher.publishEvent(event)` — a Spring event, not AMQP |
| publisher | `OrderEventPublisher` | `@Async @TransactionalEventListener(AFTER_COMMIT)` → `convertAndSend` (§21.4) |
| consumer | `OrderEventConsumer.handleOrderEvent` | `@RabbitListener(queues = "${rabbitmq.queue.name}")` |
| payload | `OrderCreatedEvent` — copied into both services | JSON, via `JacksonJsonMessageConverter` |
| broker address | `docker-compose.yml` env | `SPRING_RABBITMQ_HOST: rabbitmq` |

Note where the two halves of the configuration live. The **topology** names
(exchange, queue, routing key) come from the config server, so they can be
changed centrally. The **connection** details come from compose environment
variables, because they include the password. That split is deliberate and worth
keeping.

Confirmed on the running broker:

```
$ docker exec rabbitmq rabbitmqctl list_exchanges name type | grep order
order.exchange   topic

$ docker exec rabbitmq rabbitmqctl list_queues name durable messages consumers | grep order
order.queue      true    0    1

$ docker exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key | grep order
order.exchange   order.queue   order.tracking
```

### 21.2 Is this a real message-queue example?

Yes. Three properties have to hold, and all three do.

**The producer does not know the consumer exists.** `OrderService` names an
exchange and a routing key. It has no reference to notification-service, no
`lb://notification-service`, no Eureka lookup. Stop notification-service and
order-service neither notices nor cares — the messages queue up (`order.queue` is
durable) and are delivered when it comes back. Contrast §17, where order-service
holds an explicit `RestClient` per callee and a circuit breaker per callee
because it very much does know who it is talking to.

**The work is off the request path.** The caller of `POST /api/orders` gets its
response without waiting for whatever notification-service decides to do. Right
now the consumer only logs, but sending an email — seconds, and someone else's
uptime — would not lengthen the order response by a millisecond. That is the
whole point of the pattern. Publishing is off the request path too, since §21.4:
it happens after the commit, on a task-executor thread, so even a dead broker
does not lengthen the order response.

**Adding a second consumer requires no change to the producer.** This is what
the topic exchange buys. Today `order.tracking` is bound by exactly one queue and
matches it literally, so a `direct` exchange would behave identically. The topic
type is forward-looking: an analytics service can bind `order.*`, or a shipping
service can bind `order.shipped` once that event exists, and `OrderService` is
untouched.

One thing to be clear about, because it is the most common misreading of a setup
like this: **a second consumer needs its own queue, not a second consumer on
`order.queue`.** Two services listening on the same queue are *competing*
consumers — RabbitMQ gives each message to exactly one of them, which is load
balancing, not broadcast. Fan-out to a *different kind* of consumer means a new
queue with its own binding to `order.exchange`. Which is also why the current
queue name is slightly wrong: `order.queue` reads as "the queue for orders",
when what it actually is is "notification-service's queue of order events".
`order.notification.queue` would leave room for `order.analytics.queue` next to
it.

### 21.3 The DTO is copied, not shared — and it works for a non-obvious reason

`OrderCreatedEvent` exists twice, in different packages:

| service | class |
|---|---|
| order (producer) | `com.ramesh.order.dtos.OrderCreatedEvent` |
| notification (consumer) | `com.ramesh.notification.payload.OrderCreatedEvent` |

Copying rather than extracting a shared jar is the right call for microservices —
a shared model artifact recreates the compile-time coupling that separate
services exist to avoid. But the mechanism that makes it work here is worth
understanding, because when it breaks the error message points somewhere else
entirely.

`JacksonJsonMessageConverter` stamps every outgoing message with a `__TypeId__`
header containing the **producer's** fully-qualified class name —
`com.ramesh.order.dtos.OrderCreatedEvent`, a class that does not exist in
notification-service. If the consumer trusted that header it would fail.

It does not, because of `DefaultJacksonJavaTypeMapper.toJavaType`, whose first
move is:

```java
JavaType inferred = getInferredType(properties);
if (inferred != null && canConvert(inferred)) {
    return inferred;          // <- taken
}
String typeIdHeader = retrieveHeaderAsString(properties, getClassIdFieldName());
if (typeIdHeader != null) {
    return fromTypeHeader(properties, typeIdHeader);   // <- not reached
}
```

and `getInferredType` returns non-null only when `typePrecedence` is `INFERRED`
— which is what the no-arg constructor sets — and the message carries an
inferred-type header. `@RabbitListener` supplies that: the listener adapter reads
the method's parameter type and puts it on the message properties before
conversion. So the *listener signature* wins over the wire header, and
`handleOrderEvent(OrderCreatedEvent)` deserializes into notification's own class.

Two consequences:

- Change the listener to take `Message`, `Object`, or a generic wrapper and there
  is no inferred type to fall back on. The `__TypeId__` path runs, and it fails —
  but not with `ClassNotFoundException`. `DefaultJacksonJavaTypeMapper`'s default
  trusted packages are exactly `java.util` and `java.lang`, so the error is an
  untrusted-package rejection, which reads like a security problem rather than
  the packaging mismatch it actually is.
- The two classes must stay structurally compatible by convention alone. Nothing
  checks it. Extra fields are tolerated (Boot disables
  `FAIL_ON_UNKNOWN_PROPERTIES`), but a **new enum constant is not**: add
  `REFUNDED` to order's `OrderStatus` and notification's copy — currently an
  identical five-constant enum in a different package — throws on deserialization.
  Per §21.5 that message is then dropped, silently. `READ_UNKNOWN_ENUM_VALUES_AS_NULL`
  is the cheap guard if the enum is expected to grow.

### 21.4 The publish was inside the database transaction — fixed

The one real design problem in the first version, and the classic dual-write.
Recorded here in full because the symptom was counter-intuitive and the fix has a
second half that is easy to miss.

#### The problem

`OrderService` is annotated `@Transactional` at class level, so all of
`createOrder` ran in one JPA transaction. Inside it, in this order:

```java
Order savedOrder = orderRepository.save(order);
...
rabbitTemplate.convertAndSend(exchangeName, routingKey, event);   // (1)
cartService.clearCart(userId);                                    // (2)
return Optional.of(orderMapper.toResponse(savedOrder));           // then COMMIT
```

RabbitMQ is not enlisted in that transaction. The message left at (1),
immediately and irrevocably, while the database commit was still pending. Two
failure modes fall straight out.

**A rollback after the send produces a phantom event.** If `clearCart` throws, or
the commit fails on a constraint or a lock timeout, the order row disappears —
but notification-service has already been told the order exists.

**A broker outage takes down order placement.** Reproduced by stopping the
container mid-flow:

```
$ docker stop rabbitmq
$ curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/orders -H "X-User-ID: ..."
500
```

with, in `logs/order-service/order-service.log`:

```
ERROR ... [nio-8083-exec-4] o.a.c.c.C.[.[.[/].[dispatcherServlet] : Servlet.service() ...
threw exception [Request processing failed: org.springframework.amqp.AmqpIOException:
java.net.NoRouteToHostException: No route to host]
```

The exception propagated out of `createOrder`, the transaction rolled back, and
no order was created — the next successful order came back with `id: 3`, the
sequence having burned `2` on the rolled-back attempt. So adding asynchronous
messaging had made order placement *depend on the broker being up*, which is
close to the opposite of what a message queue is for.

#### The fix — `@TransactionalEventListener(AFTER_COMMIT)`

`OrderService` no longer knows AMQP exists. It takes an
`ApplicationEventPublisher` instead of a `RabbitTemplate`, and the send moved to
`order/events/OrderEventPublisher`:

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void publishOrderCreated(OrderCreatedEvent event) {
    try {
        rabbitTemplate.convertAndSend(exchangeName, routingKey, event);
    }
    catch (AmqpException e) {
        logger.error("LOST OrderCreatedEvent for order {}: ...", event.getOrderId(), e);
    }
}
```

Four decisions in there, none of them optional.

**The event is still built inside the transaction.** `getItems()` is a lazy JPA
collection; reading it after the transaction closes throws
`LazyInitializationException`. Flattening to DTOs while the session is open makes
the event a self-contained snapshot — which is also why the DTO is published
rather than the `Order` entity.

**`AFTER_COMMIT`** is the whole point: the listener is reached only if the commit
succeeded, so a rollback now emits nothing, and nothing the listener does can
affect the transaction that is already finished.

**The `catch` is deliberate, and it is why the method exists at all.**
`AFTER_COMMIT` callbacks are invoked from `AbstractPlatformTransactionManager`'s
`triggerAfterCommit`, *inside* the `commit()` call, and anything thrown there
propagates to the caller. Without the catch a broker outage would hand the client
a 500 for an order that is already committed — so the client retries, and creates
a duplicate. Strictly worse than the bug being fixed. `AmqpException` and not
`Exception`, because it covers both connection failures and
`MessageConversionException` (which extends it) without also swallowing an NPE.

**`fallbackExecution = true`.** The default for `@TransactionalEventListener` is
to do *nothing* when the event is published outside a transaction — no exception,
no log, the event evaporates. `createOrder` is transactional today, so the
default would work; the flag is insurance against that quietly ceasing to be
true. There is nothing to be inconsistent with in the no-transaction case,
because there is no rollback to guard against.

#### The second half — `@Async`

`AFTER_COMMIT` moves the publish off the transaction but **not off the request
thread**. That difference is measurable, and the first attempt at this fix walked
straight into it:

| | broker up | broker down |
|---|---|---|
| `AFTER_COMMIT` only | 70 ms | **3.7 s**, and the first request took longer than the gateway's 5 s TimeLimiter → **503** |
| `AFTER_COMMIT` + `@Async` | 178 ms | **65 ms** |

The order was created in every one of those cases. But a 503 for a committed
order is exactly the retry-and-duplicate outcome the `catch` was added to
prevent, so `AFTER_COMMIT` alone only half-fixed the problem — the transaction
was safe, the client still saw a failure.

`@Async` dispatches the listener to Boot's task executor. The request thread
returns as soon as the commit is done and order latency stops depending on
RabbitMQ at all. It needs `@EnableAsync` on `OrderApplication`: Boot does not
enable `@Async` by default, and without it the annotation is **silently ignored**
— the method still runs, just on the caller's thread, which is the exact
behaviour it was added to avoid. The proof that it took effect is in the log
thread name: `[task-2]` rather than `[nio-8083-exec-5]`.

It is safe here only because the event is an immutable DTO snapshot. Handing an
entity or anything holding an `EntityManager` to a pool thread would not be.

One companion setting, in `config/order-service.yml`:

```yaml
spring:
  rabbitmq:
    connection-timeout: 2s
```

The client default is 60 seconds. That never mattered while a failed publish blew
up the transaction anyway, but now it decides how long a task-executor thread is
parked when the broker is down — and Boot's default pool is small enough that
60-second parks would queue every subsequent publish behind them.

#### What is fixed, and what is not

| | before | after |
|---|---|---|
| rollback after send | phantom event | no event — listener never runs |
| broker down | **500**, no order created | **201**, order created, event lost + `ERROR` logged |
| order latency when broker is down | +3.7 s | unchanged |
| event delivery guarantee | at-most-once | at-most-once |

The last row is the one to be honest about. `@TransactionalEventListener` fixes
consistency, not durability: if the broker is unreachable at commit time the
order stands and the event is gone. That is why the catch logs at `ERROR` with
the order id — it is the only record that a consumer is missing something, and
what a reconciliation job would key off:

```
ERROR ... [task-2] c.r.order.events.OrderEventPublisher : LOST OrderCreatedEvent for
order 10: the order is committed but the event could not be published, so no consumer
will ever see it. Requires manual reconciliation.
```

The fix for that is a **transactional outbox**: write the event to an `outbox`
table in the same transaction as the order, and have a poller publish rows and
mark them sent. It is the only option that survives a broker outage, at the cost
of a table, a scheduler, and consumers that tolerate duplicates — because it
turns at-most-once into at-least-once, not exactly-once.

### 21.5 There is no dead-letter queue

`handleOrderEvent` currently only logs, so it cannot fail. The moment it does
real work, the default behaviour is a trap.

`AbstractMessageListenerContainer.defaultRequeueRejected` initialises to `true`.
A listener that throws therefore nacks the message with `requeue=true`, the
broker redelivers it, the listener throws again — at CPU speed, forever, with the
message pinned at the head of the queue and everything behind it blocked. That is
the poison-message loop, and it is the standard way a first RabbitMQ integration
takes down a service at 3am.

The other branch is worse in a different way. The default
`ConditionalRejectingErrorHandler` treats `MessageConversionException` as fatal
and rejects **without** requeue — so an unparseable message (§21.3's enum case)
is not looped, it is **dropped**. With no dead-letter exchange configured there is
nowhere for it to land, and the event is simply gone with a single WARN in the
log.

The minimum fix is a dead-letter exchange on the queue plus bounded retry:

```java
@Bean
public Queue queue() {
    return QueueBuilder.durable(queueName)
            .deadLetterExchange(dlxName)
            .deadLetterRoutingKey(dlqRoutingKey)
            .build();
}
```

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        default-requeue-rejected: false   # reject -> DLX instead of infinite loop
        retry:
          enabled: true
          max-attempts: 3
```

`default-requeue-rejected: false` is the load-bearing line: without it the DLX is
never reached, because nothing is ever rejected in the first place.

Changing the queue's arguments is not free, though — see §21.6.

### 21.6 The topology is declared twice, and ownership is backwards

`order/config/RabbitMQConfig.java` and
`notification/config/RabbitMQConfig.java` are byte-for-byte identical apart from
the `rabbitTemplate` bean. Both declare the queue, the exchange, and the binding.

Declaration is idempotent, so this works, and having the consumer declare its own
queue is genuinely useful — notification-service can start first, on an empty
broker, and still have somewhere for messages to land. But the split is the wrong
way round. The convention that scales is:

| who | declares |
|---|---|
| producer | the **exchange** — it publishes there and must know it exists |
| consumer | its **queue** and the **binding** — it owns what it reads |

As written, order-service declares a queue it never reads. Delete
notification-service and order-service still creates `order.queue` on every
start, and messages accumulate in it without bound until the broker hits its
memory watermark.

The duplication also has teeth. Queue and exchange declarations are checked for
equivalence by the broker: declaring an existing queue with different arguments
fails with `PRECONDITION_FAILED - inequivalent arg`. So the moment §21.5's
`deadLetterExchange(...)` is added to one of these two files and not the other,
whichever service starts second gets a declaration failure. The current
copy-paste guarantees that trap will be sprung eventually, because there is
nothing to remind anyone the second copy exists.

### 21.7 Beans that are already there

Two of the beans in each `RabbitMQConfig` duplicate the auto-configuration.

**`amqpAdmin`.** `RabbitAutoConfiguration$RabbitTemplateConfiguration` already
contributes one, and its body is literally `new RabbitAdmin(connectionFactory)` —
the same object, under `@ConditionalOnSingleCandidate` +
`@ConditionalOnMissingBean`. `admin.setAutoStartup(true)` is also already the
default. The hand-written bean replaces the auto-configured one with an identical
one. Harmless, but it is code that has to be read and understood by the next
person for no gain.

**`messageConverter(ObjectMapper)`.** The cast is the issue:

```java
return new JacksonJsonMessageConverter((JsonMapper) objectMapper);
```

Injecting Boot's mapper is the right instinct — it carries the `spring.jackson.*`
settings, which is why `createdAt` crosses the wire as ISO-8601
(`"2026-08-09T21:40:44.806096"`) rather than as a numeric timestamp array. But
the parameter type should be `JsonMapper`, not `ObjectMapper` plus a downcast.
Declaring any plain `ObjectMapper` bean anywhere in the application turns this
into a `ClassCastException` during context refresh — a startup crash a long way
from its cause. Asking for `JsonMapper` directly fails at injection instead, with
a message that names the missing bean.

**`rabbitTemplate` (producer only).** Declaring it by hand replaces Boot's, which
means `RabbitTemplateConfigurer` no longer runs and every
`spring.rabbitmq.template.*` property — retry, mandatory, receive timeout —
becomes dead configuration. Relevant if §21.4's publisher confirms are ever
turned on. `template.setExchange(exchangeName)` is also redundant: it sets a
default exchange, and `OrderEventPublisher` then passes the exchange explicitly on
every call anyway.

### 21.8 The application shares a broker with the Bus

`order-service` has both `spring-cloud-starter-bus-amqp` (from §14's config
refresh) and `spring-boot-starter-amqp`. They share one `ConnectionFactory` and
one broker. This is fine — the Bus uses its own `springCloudBus` exchange and
does not collide with `order.exchange` — but it means a broker outage now takes
out config refresh *and* event publishing together — though since §21.4 that no
longer takes order placement with it.

`notification-service` deliberately (or accidentally — worth deciding) has
`spring-cloud-starter-config` **without** `spring-cloud-starter-bus-amqp`. It
therefore reads config from the config server at startup but does **not** respond
to `POST /actuator/busrefresh`. Changing `rabbitmq.queue.name` in
`config/notification-service.yml` needs a restart of that service, while the same
change in `config/order-service.yml` propagates over the bus. Since the service
is already connected to RabbitMQ, adding the bus starter is a one-line change if
the inconsistency is not wanted.

Two smaller gaps in the same family:

- `notification-service` has **no healthcheck** in `docker-compose.yml`, unlike
  every other application service. Nothing depends on it, so nothing blocks, but
  `docker compose up --wait` will report success while it is still starting.
- Its actuator is on 9090 like everything else (§16), and unpublished. The `8084`
  mapping publishes the application port, which serves nothing — the service has
  no controllers. Harmless, and useful the moment it grows one.

### 21.9 Observing it

The end-to-end path, from the host:

```bash
curl -X POST http://localhost:8080/api/carts \
  -H "Content-Type: application/json" -H "X-User-ID: <user-id>" \
  -d '{"productId":"1","quantity":3}'

curl -X POST http://localhost:8080/api/orders -H "X-User-ID: <user-id>"

docker logs ecom_notification | grep "Received order event"
```

Broker-side, all without credentials — `rabbitmqctl` authenticates with the
Erlang cookie, unlike the management UI on :15672:

```bash
docker exec rabbitmq rabbitmqctl list_queues name messages consumers
docker exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key
```

What each number means when something is wrong:

| symptom | reading |
|---|---|
| `consumers 0` | notification-service is down or never bound — messages are accumulating |
| `messages` climbing | consumer slower than producer, or stuck on a redelivery loop (§21.5) |
| `messages 0`, nothing logged | the message never arrived — wrong routing key, or publish silently unrouted |
| queue absent entirely | neither service started, or a declaration failed with `PRECONDITION_FAILED` (§21.6) |

The last row is the one to check first after editing either `RabbitMQConfig`.

Since §21.4 the order response tells you nothing about whether the publish
succeeded — that is the point of it, and it means the producer side has its own
place to look:

```bash
docker logs ecom_order | grep "LOST OrderCreatedEvent"
```

A hit there means the order committed and the event did not, which is the one
failure this design accepts and cannot recover from on its own.

### Verification status

Exercised at runtime, on the full stack.

Verified by running it:

- `POST /api/orders` → `201`, and notification-service logged
  `Received order event: OrderCreatedEvent(orderId=1, userId=6a78f3c8..., status=CONFIRMED,
  items=[OrderItemDto(...)], totalAmount=15.00, createdAt=2026-08-09T21:40:44.806096)`
  within ~160 ms of the response
- the broker reported `order.exchange` (topic), `order.queue` (durable, 1
  consumer), bound with `order.tracking`
- the cross-package DTO copy deserialized correctly, which is itself the proof
  that the inferred type beat the `__TypeId__` header (§21.3) — the header's
  class does not exist in notification-service
- `LocalDateTime` round-tripped as an ISO-8601 string, not a timestamp array
- queue depth returned to `0` after each consumed message
- with `ecom_notification` stopped, `POST /api/orders` still returned `201`,
  `order.queue` held `messages 1 / consumers 0`, and restarting the container
  delivered it — the durability claim in §21.2, confirmed

§21.4, before the fix:

- with `rabbitmq` stopped, `POST /api/orders` returned **500** with
  `AmqpIOException` out of `dispatcherServlet`, and the order was **not**
  persisted — the following successful order was `id: 3`, id `2` having been
  consumed by the rolled-back attempt

§21.4, after the fix:

- happy path unchanged: `201`, order `9`, consumed by notification-service ~140 ms
  later
- with `rabbitmq` stopped: `201` in **65 ms**, order `10` committed, and
  `LOST OrderCreatedEvent for order 10` logged at `ERROR` — the order survives, the
  event does not
- the `ERROR` was logged on thread `[task-2]`, not `[nio-8083-exec-5]`, which is
  the proof `@Async` took effect
- the intermediate state — `AFTER_COMMIT` without `@Async` — was also measured,
  and is why `@Async` is there: `201` but **3.7 s** with the broker down, and the
  first such request exceeded the gateway's 5 s TimeLimiter and came back **503**
  for an order that had been created
- `spring.rabbitmq.connection-timeout` confirmed bound from
  `configserver:file:/app/config/order-service.yml` via `/actuator/env`

Verified against compiled bytecode in `~/.m2`:

- `DefaultJacksonJavaTypeMapper()` sets `typePrecedence = INFERRED`, and
  `toJavaType` checks `getInferredType` before the `__TypeId__` header
  (spring-amqp 4.1.0) (§21.3)
- its `TRUSTED_PACKAGES` static initialiser contains only `java.util` and
  `java.lang` (§21.3)
- `AbstractMessageListenerContainer` initialises `defaultRequeueRejected` to
  `true` (spring-rabbit 4.1.0) (§21.5)
- `RabbitAutoConfiguration$RabbitTemplateConfiguration.amqpAdmin` is
  `new RabbitAdmin(connectionFactory)` under `@ConditionalOnSingleCandidate` +
  `@ConditionalOnMissingBean` (spring-boot-amqp 4.1.0) (§21.7)
- `spring.rabbitmq.listener.simple.default-requeue-rejected` and
  `...retry.max-attempts` exist in `spring-configuration-metadata.json` (§21.5)

Not verified: the poison-message loop and the dead-letter path were reasoned from
the defaults above, not triggered — the consumer cannot currently throw. The
phantom-event case in §21.4 was never forced in either direction; it needs a
failure injected between the save and the commit, and only the broker-down half
was reproduced. Its absence after the fix is guaranteed by the `AFTER_COMMIT`
contract rather than by observation. No `PRECONDITION_FAILED` was provoked
(§21.6).


## 22. Messaging, take 2 — Spring Cloud Stream over the same RabbitMQ

§21 wired RabbitMQ by hand: `RabbitTemplate`, `@RabbitListener`, and a
`RabbitMQConfig` in each service declaring the exchange, the queue and the
binding. This section replaces that plumbing with **Spring Cloud Stream** while
keeping the same broker, the same event, and the same producer design.

> **This is stage 2 of 3, and it is no longer the live configuration.** §23
> swapped the binder for Kafka, so the Rabbit binder described here is not what
> runs today — RabbitMQ now carries only Spring Cloud Bus (§16). The section is
> kept because the Stream concepts introduced here (binding names, `StreamBridge`,
> `group`) are exactly what carried over unchanged, which is §23's main finding.
> Where §23 supersedes something below it says so.

The same messaging flow is documented three times in this file, deliberately,
because the interesting part is what survives each move:

| stage | section | producer API | consumer API | topology declared by |
|---|---|---|---|---|
| 1. native AMQP | §21 | `RabbitTemplate.convertAndSend` | `@RabbitListener` | hand-written `RabbitMQConfig` |
| 2. Stream, Rabbit binder | §22 (this) | `StreamBridge.send` | `Consumer<T>` bean | the binder, from YAML |
| 3. Stream, Kafka binder | §23 | `StreamBridge.send` — unchanged | `Consumer<T>` bean — unchanged | the binder, from YAML |

Read that table left to right and the point of stage 2 is already visible: it is
the step that makes stage 3 a dependency swap rather than a rewrite.

### 22.1 What actually changed, and what did not

Dependencies, in both `order/pom.xml` and `notification/pom.xml`:

```xml
<!-- removed (commented out) -->
<!-- <artifactId>spring-boot-starter-amqp</artifactId> -->

<!-- added -->
<artifactId>spring-cloud-stream</artifactId>
<artifactId>spring-cloud-stream-binder-rabbit</artifactId>
```

`spring-cloud-stream-binder-rabbit` pulls `spring-rabbit` transitively, which is
why the leftover `RabbitMQConfig` classes still compile (§22.6).

What did **not** change is more interesting:

- **`OrderService` is untouched.** It still calls
  `eventPublisher.publishEvent(event)` — a Spring application event. It knew
  nothing about AMQP before and knows nothing about Stream now. The §21.4
  refactor paid for itself here: because the broker call had already been pushed
  out into `OrderEventPublisher`, swapping the entire messaging stack touched one
  class.
- **`@Async @TransactionalEventListener(AFTER_COMMIT)` is unchanged**, and still
  necessary for exactly the reasons in §21.4. Stream does not participate in the
  JPA transaction either, so the dual-write problem is identical.
- **Connection configuration is unchanged.** The Rabbit binder builds on Boot's
  ordinary `spring.rabbitmq.*` properties, so `SPRING_RABBITMQ_HOST` and friends
  from `docker-compose.yml` — and `connection-timeout: 2s` from
  `config/order-service.yml` — carry over untouched.
- **The payload is unchanged.** Still `OrderCreatedEvent` as JSON, still copied
  into both services. The §21.3 discussion of `__TypeId__` vs inferred types no
  longer applies — Stream uses its own `MessageConverter` chain, and the target
  type comes from the `Consumer<OrderCreatedEvent>` generic — but the practical
  outcome is the same: the cross-package copy deserializes fine.

### 22.2 The binding name is the whole API

Everything in Stream hangs off a **binding name**, and the convention is
mechanical:

```
<functionName>-in-<index>      inputs
<functionName>-out-<index>     outputs
```

The index is for functions with multiple inputs or outputs; with one of each it
is always `0`. So the `Consumer` bean named `orderCreated` produces the binding
name `orderCreated-in-0`, and that string is what the YAML configures:

```yaml
# config/notification-service.yml
spring:
  cloud:
    function:
      definition: orderCreated          # names the bean to bind
    stream:
      bindings:
        orderCreated-in-0:
          destination: order.exchange
          content-type: application/json
          group: notification
```

Two traps in that block.

**`spring.cloud.function.definition`, not `spring.cloud.stream.function
.definition`.** The latter was the Spring Cloud Stream 3.x spelling. It is gone
in 4.x / 2025.x and binds to nothing — silently. Both were briefly set to the
same value here, which worked only because the correct one was among them. The
dead property has been removed rather than left in place.

**With exactly one function bean, `definition` is optional** — Stream will find
it. It is set anyway so that adding a second `Consumer` later fails loudly
instead of one being chosen arbitrarily.

### 22.3 Producing — `StreamBridge`, and the dynamic-destination trap

There is no `@Output` channel interface any more (that was the deprecated
`@EnableBinding` model). An arbitrary piece of code publishes through
`StreamBridge`:

```java
private static final String BINDING = "publishOrderCreated-out-0";

private final StreamBridge streamBridge;

@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void publishOrderCreated(OrderCreatedEvent event) {
    try {
        streamBridge.send(BINDING, event);
    }
    catch (MessagingException e) { ... }
}
```

with the destination in YAML:

```yaml
# config/order-service.yml
spring:
  cloud:
    stream:
      bindings:
        publishOrderCreated-out-0:
          destination: order.exchange
          content-type: application/json
      rabbit:
        bindings:
          publishOrderCreated-out-0:
            producer:
              routing-key-expression: '''order.tracking'''
```

Note the binding name here is arbitrary — nothing is named
`publishOrderCreated` on the function side. It is a label chosen to match the
method, and `StreamBridge` looks it up in `spring.cloud.stream.bindings`.

**And that lookup is the trap that cost an afternoon.** The first version of this
had a one-character typo — `publishOrderCreate-out-0`, missing the `d`. That is
not an error in Stream. An unrecognised binding name is treated as a **dynamic
destination**: the binder creates a new exchange named after the string it was
given and publishes there. `send()` returned `true` every time.

The broker made it obvious once looked at directly:

```
$ docker exec rabbitmq rabbitmqctl list_exchanges name type
order.exchange              topic
publishOrderCreate-out-0    topic      <- conjured by the typo
springCloudBus              topic

$ docker exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key
order.exchange   order.queue                                     order.tracking
order.exchange   order.exchange.anonymous.ltM6SZplQyu55LQB0YxkDg  #
```

`publishOrderCreate-out-0` appears in `list_exchanges` and **not once** in
`list_bindings`. A topic exchange with no bindings discards everything published
to it, without error, without a log line, and without any `returned-message`
callback unless publisher returns are explicitly enabled. Producer healthy,
consumer healthy, connected to the same broker, zero messages.

The symptom is worth remembering because it is entirely silent from both ends:

- order-service logged a successful publish
- notification-service logged nothing at all
- the RabbitMQ UI showed `order.queue` sitting at 0 messages, which looked like
  "the producer is broken" but was actually a *third* queue nobody was using
  (§22.6)

The binding name is now a `static final String` referenced once, which is the
cheapest available defence: a mistyped constant fails to compile, a mistyped
literal creates an exchange.

**On `routing-key-expression: '''order.tracking'''`** — the triple single quotes
are not a typo, and they are not three quotes doing one job. Two independent
parsers each want a layer:

```
YAML source:      '''order.tracking'''
after YAML:        'order.tracking'      <- a SpEL string literal
after SpEL:         order.tracking       <- the routing key on the wire
```

The property is `routingKeyExpression`, a **SpEL expression** evaluated against
the outbound message — not a plain string. Written bare, `order.tracking` is
valid SpEL meaning "the `order` property of the message, then its `tracking`
property", which fails at runtime because `Message` has no `order` property. A
literal string in SpEL is single-quoted, so the value must be
`'order.tracking'`, quotes included.

Getting those quotes through YAML is the second layer. Inside a single-quoted
YAML scalar, a literal `'` is written by **doubling** it. So the outer pair
delimits the scalar and each inner `''` collapses to one quote:

```
'   ''   order.tracking   ''   '
^   ^^                    ^^   ^
|   one literal quote     |    closing delimiter
opening delimiter         one literal quote
```

Double quotes avoid the doubling and are easier to read, which is worth
preferring:

```yaml
routing-key-expression: "'order.tracking'"
```

The rule generalises to every `*-expression` property in Spring Cloud Stream —
`partition-key-expression`, `binding-routing-key-expression` and the rest are
SpEL too, so a constant always needs its own inner quotes. It also explains why
a mistake here surfaces late: SpEL is parsed lazily, so a bare value binds fine
at startup and only fails on the first message.

Its practical effect is currently nil, and that is worth understanding rather
than cargo-culting: the consumer side binds its queue with `#`, so every routing
key matches. Confirmed on the broker for both the anonymous queue and the
grouped one — `group` changes the queue's name and lifetime, not its binding
key, which stays `#` until `binding-routing-key` is set explicitly. The routing key only starts doing work once a
consumer sets `binding-routing-key` to filter — which is the natural place to
grow this example, since `order.exchange` is a topic exchange and could just as
easily carry `order.cancelled` or `order.shipped`.

### 22.4 Consuming — a `Consumer<T>` bean, and why `group` is not optional

`@RabbitListener` is gone. The consumer is a plain function bean:

```java
@Configuration
public class OrderEventConsumer {

    @Bean
    public Consumer<OrderCreatedEvent> orderCreated() {
        return event -> {
            logger.info("Received order created event for order: {}", event.getOrderId());
            logger.info("Received order crated event for user: {}", event.getUserId());
        };
    }
}
```

No annotation ties this to RabbitMQ, to a queue name, or to Stream. The bean name
selects the binding (§22.2) and the generic parameter selects the deserialization
target. This is the class that survives the move to Kafka completely unchanged.

**`group` is the setting to get right.** Without it the binder creates an
**anonymous, auto-delete, exclusive** queue — seen above as
`order.exchange.anonymous.ltM6SZplQyu55LQB0YxkDg`. It disappears the moment
notification-service disconnects, so any event published while the service is
down is gone permanently.

That is a real regression against §21, where `order.queue` was durable and the
stop-the-consumer test showed `messages 1 / consumers 0` followed by delivery on
restart. Dropping in Stream without a `group` would have silently thrown that
property away — a demo that still works on the happy path and quietly loses
messages the first time a consumer restarts.

With `group: notification` the queue is durable and named `<destination>.<group>`
— `order.exchange.notification`. The group is also the unit of consumption
semantics, and the distinction matters:

| | effect |
|---|---|
| second instance, **same** group | shares the one queue — competing consumers, load split |
| second service, **different** group | gets its own queue and a full copy of every message — fan-out |

This is the same competing-vs-broadcast distinction noted in §21, but expressed
declaratively instead of by hand-declaring a second queue and binding.

### 22.5 The regression Stream introduced in the §21.4 fix

The `catch` block from §21.4 still compiled after the move to `StreamBridge`, and
would never have fired again:

```java
catch (AmqpException e) {          // dead as soon as the send moved to StreamBridge
```

`StreamBridge` hands the message to a Spring Integration channel, and
`AbstractMessageHandler` wraps whatever the outbound endpoint throws in a
`MessageHandlingException`. A dead broker's `AmqpException` therefore arrives as
the **cause** of an `org.springframework.messaging.MessagingException`, not as
itself.

Left alone, the at-most-once safety net from §21.4 would have gone quietly dead:
no `LOST OrderCreatedEvent` line, and the exception escaping into the `@Async`
executor's uncaught-exception handler instead. Corrected to:

```java
catch (MessagingException e) {
```

still narrower than `Exception`, so an NPE inside the method stays visible.

This is the second silent failure in one migration, and both share a shape:
**the compiler cannot see either of them.** A binding name is a string, and an
exception type that no longer occurs is still a valid type.

### 22.6 Leftovers — `RabbitMQConfig` and the ghost `order.queue` (removed)

Both `RabbitMQConfig` classes outlived the migration. They still compiled,
because the Rabbit binder brings `spring-rabbit` transitively, and their
`RabbitAdmin` still declared the old topology at every startup. Hence a queue
that no code at either end used:

```
order.queue    durable    0 consumers    <- still bound to order.exchange with order.tracking
```

It was not merely inert, and this is the part worth keeping. Because the binding
lived on the *broker*, not in the application, `order.queue` kept matching the
producer's `order.tracking` routing key and **silently accumulated a second copy
of every event** — observed climbing 0 → 1 → 2 → 3 across the verification runs,
with nothing consuming it. An unbounded leak sitting behind a working feature.

It was also actively misleading while debugging §22.3: `order.queue` is what the
RabbitMQ UI shows when looking for "the order queue", so an empty one read as a
broken producer rather than as an orphan next to the real queue.

Both classes are now **deleted**, along with the `rabbitmq.exchange.name` /
`queue.name` / `routing.key` blocks in `config/order-service.yml` and
`config/notification-service.yml` — `@Value` in those classes was their only
consumer. Removing them also disposes of the redundant `amqpAdmin` bean and the
`(JsonMapper) objectMapper` downcast flagged in §21.7, and of the
`PRECONDITION_FAILED - inequivalent arg` risk in §21.6: with only the binder
declaring `order.exchange`, there is no second declaration left to diverge from.

Deleting the classes does not remove broker state, since queues and bindings
survive the application that declared them. `order.queue` was dropped explicitly
(`rabbitmqctl delete_queue order.queue` — "successfully deleted with 3 ready
messages", the leaked copies), as was the phantom `publishOrderCreate-out-0`
exchange from the §22.3 typo.

Note what remains commented rather than deleted: the `@RabbitListener` block in
`OrderEventConsumer` and the `RabbitTemplate` fields in `OrderEventPublisher`.
Both reference `${rabbitmq.*}` properties that no longer exist, so uncommenting
either now fails at startup on an unresolvable placeholder.

### 22.7 What Stream buys, and what it costs

Honest accounting, because "it's an abstraction" is not by itself a reason.

**Buys:**

- **The broker becomes a dependency, not a design.** `OrderEventPublisher` and
  `OrderEventConsumer` contain no AMQP types. §23 is a binder swap plus
  destination renaming.
- **Topology from configuration.** Two `RabbitMQConfig` classes, ~60 lines of
  duplicated `@Bean` declarations with ownership backwards (§21.6), collapse into
  a handful of YAML keys served by the config server.
- **The DLQ from §21.5 stops being work.** The missing dead-letter path — the one
  real gap left open in §21 — is now:

  ```yaml
  spring:
    cloud:
      stream:
        bindings:
          orderCreated-in-0:
            consumer:
              max-attempts: 3
        rabbit:
          bindings:
            orderCreated-in-0:
              consumer:
                auto-bind-dlq: true
                republish-to-dlq: true
  ```

  which declares `order.exchange.notification.dlq`, binds it, and stops the
  poison-message requeue loop that `defaultRequeueRejected: true` would otherwise
  cause. **Not yet applied.**

**Costs:**

- **Silent misconfiguration.** Both §22.3 and §22.5 are failures that native
  AMQP would have surfaced: `convertAndSend` to a nonexistent exchange with a
  typo'd `@Value` at least fails on the placeholder.
- **A layer to debug through.** The path is now `StreamBridge` → binding →
  `MessageChannel` → Spring Integration → binder → `AmqpOutboundEndpoint` →
  broker. Stack traces get long and the failure is often two layers below the
  name in the config.
- **Naming conventions to know.** `-in-0`/`-out-0`, `<destination>.<group>`,
  `spring.cloud.stream.rabbit.bindings.*` for binder-specific settings versus
  `spring.cloud.stream.bindings.*` for portable ones. None of it is discoverable
  from the code.

**Does not buy:** delivery guarantees. This is still **at-most-once**. Stream
changes the API, not the fact that the broker is not enlisted in the database
transaction. The §21.4 conclusion is unchanged and so is its remedy — a
transactional outbox is the only thing that survives a broker outage, and it
buys at-least-once, not exactly-once.

### 22.8 Verification status

Observed on the running broker, before the fixes in this section:

- `publishOrderCreate-out-0` present in `list_exchanges` as a topic exchange, and
  absent from `list_bindings` — the dynamic destination created by the typo
- `order.exchange.anonymous.ltM6SZplQyu55LQB0YxkDg` with `1` consumer, bound to
  `order.exchange` with routing key `#` — notification-service was correctly
  connected and subscribed the entire time
- `order.queue` at `0 messages / 0 consumers`, bound with `order.tracking` — the
  orphan from `RabbitMQConfig` (§22.6)
- notification-service logged nothing, which was the reported symptom

Observed after rebuilding with the §22.3–§22.5 corrections:

- the binder created `order.exchange.notification` — `durable true`,
  `auto_delete false`, `1` consumer — bound to `order.exchange` with `#`,
  confirming both the `group` behaviour in §22.4 and the routing-key claim in
  §22.3
- `POST /api/orders` → `201` in **60 ms**, order `3`, and notification-service
  logged `Received order created event for order: 3` **46 ms** after the
  response, on container thread `[.notification-1]` and carrying the same trace
  id as the request
- the DTO deserialized correctly across the package copy, as under
  `@RabbitListener`
- **durability**, the test an ungrouped binding would fail: with
  `ecom_notification` stopped, the queue survived at `durable true / 0
  consumers`, `POST /api/orders` still returned `201` (order `4`), the queue held
  `1` message, and restarting the container delivered it —
  `Received order created event for order: 4`
- the §22.6 leak was caught here: `order.queue` climbed to `3` ready messages
  across these runs with no consumer

Observed after deleting `RabbitMQConfig` and the orphan broker objects (§22.6):

- `list_exchanges` shows only `order.exchange` and `springCloudBus`; the phantom
  `publishOrderCreate-out-0` is gone
- `list_queues` shows one application queue, `order.exchange.notification`
  (`0 messages / 1 consumer`) — no second copy accumulating anywhere
- `POST /api/orders` → `201`, order `5`, consumed, queue back to `0`

**Still not verified.** Broker-down behaviour through `StreamBridge` — that `201`
is still returned and that `LOST OrderCreatedEvent` is still logged now the
`catch` type has changed from `AmqpException` to `MessagingException` (§22.5).
The §21.4 broker-down test has not been re-run against the Stream path, so that
safety net is reasoned from the wrapping behaviour of
`AbstractMessageHandler`, not observed. Also unverified, as in §21: the
phantom-event case, which needs a failure injected between the save and the
commit.

## 23. Messaging, take 3 — Spring Cloud Stream over Kafka

The same `OrderCreatedEvent` flow again, third transport. §21 was native AMQP,
§22 put Spring Cloud Stream in front of RabbitMQ, and this section swaps the
binder underneath Stream for Kafka.

### 23.1 The premise, and whether it held

§22 claimed the point of the Stream layer was to make this step a dependency
swap rather than a rewrite, and set a falsifiable test: **if the migration
touches `OrderEventPublisher` or `OrderEventConsumer`, that is the finding.**

It did not. **Zero Java changed.** `OrderEventPublisher` still calls
`streamBridge.send(BINDING, event)` behind
`@Async @TransactionalEventListener(AFTER_COMMIT)`; `OrderEventConsumer` is still

```java
@Bean
public Consumer<OrderCreatedEvent> orderCreated() { ... }
```

compiled against no Kafka type and no AMQP type. Everything that moved was
dependencies, YAML, and `docker-compose.yml`.

That is the strongest evidence in this document for the §22 abstraction being
worth its costs — and it is worth weighing against the fact that every failure in
this migration (§23.2, §23.3) was a *silent* one, which is the same abstraction's
bill coming due.

### 23.2 The broker — KRaft, dual listeners, and three silent typos

No ZooKeeper. `confluentinc/cp-kafka:7.6.0` in KRaft mode, one container acting
as both broker and controller:

```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_NODE_ID: 1
KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:29093"
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
CLUSTER_ID: "mBrNMiZzQ7ihee_MZJ7FcQ"
```

**The dual listener is the part to understand**, because everything downstream
depends on it:

```yaml
KAFKA_LISTENERS:            PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:29092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
```

A Kafka client does not keep talking to the address it was given. It bootstraps,
receives the **advertised** address of the partition leader, and reconnects to
*that*. So one address cannot serve both callers here: containers need
`kafka:9092` (Docker DNS) and the host needs `localhost:29092` (published port).
Two listeners, two advertised names, and the client picks its bootstrap
accordingly. Getting this wrong produces a connection that succeeds and then
immediately fails on an unreachable advertised address — a confusing failure that
this setup avoids by construction.

Three separate typos cost more time than the design did, and **all three were
silent**:

| typo | what actually happened |
|---|---|
| `kafka_broker-api-versions` in the healthcheck (underscore, not hyphen) | every probe exited with `exec: "kafka_broker-api-versions": executable file not found in $PATH`, so the container sat at `Up (unhealthy)` forever and every `depends_on: condition: service_healthy` blocked — while the broker itself was perfectly fine and logging `Kafka Server started` |
| no `networks:` key on the `kafka` service | Compose attached it to the implicit `ecom_microservices_default` network while everything else was on `ecom-network`, so the name `kafka` did not resolve |
| `KAFKA_GROUP_INITIAL_REBLANCE_DELAY_MS` ("REBLANCE") | the image's configure script only translates `KAFKA_*` names it recognises, so an unknown one is dropped without complaint and the broker started with the 3 s default |

The first is worth dwelling on: **a broken healthcheck is indistinguishable from
a broken service** from the outside. `docker compose up -d` failing looked like
Kafka failing to start; `docker logs ecom_kafka` said `Kafka Server started` and
`Awaiting socket connections on 0.0.0.0:9092`. The diagnosis came from
`docker inspect ecom_kafka --format '{{json .State.Health}}'`, which prints the
probe's own stderr — the fastest way to separate the two.

### 23.3 Configuration — and the misleading exception chain

Three edits, two of which failed first.

**1. `brokers`, with a default that suits both callers:**

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          brokers: ${KAFKA_BROKERS:localhost:29092}
```

with `KAFKA_BROKERS: kafka:9092` in `docker-compose.yml` for order-service and
notification-service. The default is the host-side listener so an IDE run needs
no extra config; the compose override is the in-network one. This follows §21.1's
split — topology in the config server, connection details in compose — with the
wrinkle that here there is no password, so the default can safely live in the
config file.

The first attempt hardcoded `brokers: localhost:29092`, which inside a container
points at the container itself. The failure did not say so:

```
ERROR o.s.cloud.stream.binding.BindingService : Failed to create consumer binding; retrying in 30 seconds
org.springframework.cloud.stream.provisioning.ProvisioningException:
    Provisioning exception encountered for order.exchange
Caused by: java.util.concurrent.TimeoutException
```

preceded by hundreds of `[AdminClient clientId=adminclient-5] Metadata update
failed` / `The AdminClient thread has exited` lines. Naming `order.exchange` in
the message makes it read as a topic problem — an invalid name, a missing topic,
a bad `destination`. It is not: the AdminClient simply never reached a broker,
and the provisioning step is just where the timeout surfaced. (`order.exchange`
*is* a legal topic name; Kafka allows `[a-zA-Z0-9._-]`.)

Fixing the address moved the failure one layer down, to a chain worth recording
in full because only the innermost line is informative:

```
BinderException: Exception thrown while starting consumer
  Caused by: KafkaException: Failed to create new KafkaAdminClient
    Caused by: ConfigException: No resolvable bootstrap urls given in bootstrap.servers
```

That innermost line is DNS: `kafka` did not resolve, because of the missing
`networks:` key in §23.2. Confirmed directly rather than guessed:

```
$ docker inspect ecom_kafka --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
ecom_microservices_default
$ docker inspect ecom_order --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
ecom-network
```

The general lesson: `bootstrap.servers` was also visible in the logs as
`bootstrap.servers = [kafka:9092]`, which proved the *property* was correct and
therefore that the problem was below Spring entirely. Reading that line first
would have skipped a step.

**2. `group` moved back onto the binding.** During the edit it drifted under
`spring.cloud.stream.kafka.binder`, where no such property exists — accepted,
ignored, no warning. It belongs on the binding, is binder-independent, and is
byte-identical to the RabbitMQ version:

```yaml
bindings:
  orderCreated-in-0:
    destination: order.exchange
    content-type: application/json
    group: notification
```

**3. The Rabbit producer block has no Kafka equivalent** and is commented out
rather than deleted, as a marker of what did not port:

```yaml
#      rabbit:
#        bindings:
#          publishOrderCreated-out-0:
#            producer:
#              routing-key-expression: '''order.tracking'''
```

A topic exchange routes on a key with wildcards. Kafka has no routing layer at
all. The nearest thing, `partition-key-expression`, chooses a *partition* within
one topic — it controls ordering and consumer assignment, not who receives what.
Filtering by event type has to move into the payload, into headers, or into
separate topics. This is the one genuinely non-portable piece of §22.

### 23.4 What `group` means now

The same word, the same YAML line, a different mechanism — and this is the most
interesting thing in the migration.

| | Rabbit binder (§22.4) | Kafka binder |
|---|---|---|
| creates | durable queue `order.exchange.notification` | nothing; a consumer-group entry in `__consumer_offsets` |
| position tracked by | queue contents — an acked message is deleted | a committed **offset** per partition |
| consumer restarts | resumes because unconsumed messages are still queued | resumes because the offset is stored broker-side |
| second instance, same group | competing consumers on one queue | **partitions** are reassigned across members |
| omitted | anonymous auto-delete queue; messages lost while disconnected | anonymous group starting at `latest`; messages skipped while disconnected |

The failure mode of forgetting `group` is the same in effect — silently miss
everything published while the consumer was down — and completely different in
mechanism. Neither binder warns.

The partition point has a live consequence here. The topic auto-created with
binder defaults:

```
Topic: order.exchange   PartitionCount: 1   ReplicationFactor: 1
```

With one partition, the group can never have more than one *active* consumer: a
second notification-service instance would join, be assigned nothing, and idle.
Rabbit's competing consumers needed no such planning. Setting `partition-count`
explicitly is the fix, and it must be decided before the topic exists.

### 23.5 Verified behaviour

Observed on the running stack.

**Happy path.** `POST /api/orders` → `201` in 105 ms, order `3`, consumed **59
ms** after the response (`createdAt 03:10:51.996` → log `03:10:52.055`), on
thread `[container-0-C-1]`, carrying the request's trace id.

The thread name is itself a proof of transport: Kafka's
`KafkaMessageListenerContainer` names its threads `container-0-C-1`, where the
Rabbit binder used `[.notification-1]` after the queue. Same log statement, same
class, different machinery.

**First message is not representative.** Order `2` took **3.0 s**
(`03:03:17.345` → `03:03:20.387`) — topic auto-creation plus the initial
consumer-group join. Steady state is the 59 ms above. Measuring only the first
message would have produced a badly wrong comparison against §22's 46 ms.

**Offsets advance and lag returns to zero:**

```
GROUP         TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
notification  order.exchange  0          2               2               0
```

**Durability / resume-from-offset**, the counterpart of §22.8's stop-the-consumer
test. With `ecom_notification` stopped, `POST /api/orders` still returned `201`
(order `4`) and the group showed the producer running ahead of the consumer:

```
notification  order.exchange  0   2   3   1   -   -   -      <- LAG 1, no member
```

On restart the message was delivered and the offset caught up:

```
notification  order.exchange  0   3   3   0   consumer-notification-2-83de81a9-...
2026-08-13T03:12:11.346Z INFO [container-0-C-1] ... Received order created event for order: 4
```

Note the `CONSUMER-ID` changed — a new member joined the same group. The *group*
is durable, not the connection.

**Replay, the property RabbitMQ does not have.** After the message was consumed
and its offset committed, it is still on the topic:

```
$ docker exec ecom_kafka kafka-console-consumer --bootstrap-server localhost:29092 \
    --topic order.exchange --from-beginning --max-messages 1
{"orderId":2,"userId":"6a7d2e920969ee9d318fd4c1","status":"CONFIRMED",
 "items":[{"id":2,"productId":"1","quantity":10,"price":10.00,"subTotal":100.00}],
 "totalAmount":100.00,"createdAt":"2026-08-13T03:03:17.345081"}
```

Under Rabbit this message would have been deleted on ack. A new consumer group
could read the whole history from offset 0 — which is what makes an outbox-style
audit or a rebuilt read model possible without the producer's involvement.

**RabbitMQ is no longer on the application path.** `rabbitmqctl list_queues`
returns no application queues; only the `springCloudBus` anonymous ones remain,
because Spring Cloud Bus still uses RabbitMQ (§16). Both brokers run; only one
carries `OrderCreatedEvent`.

> Superseded by **§28**: the Bus moved to Kafka as well and the RabbitMQ container
> was removed. Those `springCloudBus` anonymous queues are now anonymous Kafka
> consumer groups — with one difference that matters, since Kafka has no
> auto-delete equivalent and they accumulate (§28.4).

**Not verified.** Broker-down behaviour through the Kafka binder — that
`POST /api/orders` still returns `201` and `LOST OrderCreatedEvent` is logged
when Kafka is unreachable. This is the same gap flagged at the end of §22.8, and
it is now *less* certain: the `catch (MessagingException)` was chosen for how
Spring Integration wraps the Rabbit outbound endpoint, and the Kafka path throws
`KafkaException` / `TimeoutException` from a different place. Whether they arrive
wrapped in a `MessagingException` has not been tested. Also unverified, as in §21
and §22: the phantom-event case.

### 23.6 The three stacks, side by side

| | §21 native AMQP | §22 Stream + Rabbit | §23 Stream + Kafka |
|---|---|---|---|
| producer API | `RabbitTemplate.convertAndSend` | `StreamBridge.send` | `StreamBridge.send` — unchanged |
| consumer API | `@RabbitListener` | `Consumer<T>` bean | `Consumer<T>` bean — unchanged |
| topology declared by | hand-written `RabbitMQConfig` | binder, from YAML | binder, from YAML |
| destination | exchange `order.exchange` | exchange `order.exchange` | **topic** `order.exchange` |
| subscription unit | queue `order.queue` | queue `order.exchange.notification` | consumer group `notification` |
| position tracking | queue contents | queue contents | committed offset |
| after consumption | deleted | deleted | **retained — replayable** |
| routing | `order.tracking` + wildcards | same | none; partitions instead |
| steady-state latency | ~160 ms | ~46 ms | ~59 ms |
| survives consumer restart | yes | yes (with `group`) | yes (with `group`) |
| delivery guarantee | **at-most-once** | **at-most-once** | **at-most-once** |

The last row has not moved in three migrations, and that is the conclusion worth
carrying out of all three sections. Every one of these stacks publishes after the
JPA commit and outside it, so an unreachable broker means a committed order whose
event never existed. Kafka's durability, replay and offset tracking are all
properties of messages that *reached* the log. None of them help with the gap
between the commit and the send.

The fix has been named three times and built zero times: a **transactional
outbox** — write the event to a table in the same transaction as the order, poll
and publish separately, mark rows sent. It converts at-most-once into
at-least-once, which is why the consumer must become idempotent as part of the
same change. On Kafka that lands more naturally than on Rabbit, since the
consumer already tracks an offset and the topic already retains history.

Remaining cleanup, unrelated to correctness:

- the topic is still named `order.exchange`, an AMQP word for a Kafka concept;
  `order.created` is the honest name, and renaming requires both sides to move
  together. Kafka also warns about names mixing `.` and `_`, because it collapses
  both to `_` in metric names, so two topics can silently share one metric
- `partition-count` should be set deliberately rather than defaulted to 1 (§23.4)
- the DLQ is still not configured — `enable-dlq` producing to `order.exchange.DLT`
  is the Kafka spelling of the §22.7 block, and the gap first noted in §21.5
- `spring-cloud-stream-binder-rabbit` and the `spring.rabbitmq.*` settings can
  come out of order-service and notification-service once Kafka is the committed
  choice; RabbitMQ itself stays for the config bus

---

## 24. Keycloak — authentication, identity propagation, and user provisioning

The first section in this document about **who** a request is from, rather than
where it goes or how fast it gets there. Three things had to become true, and
they are genuinely separate concerns that fail in different ways:

| Concern | Question it answers | Who owns it |
|---|---|---|
| Authentication | *May* this request proceed? | `SecurityConfig` (gateway) |
| Provenance | *Who* does this request claim to be? | `UserIdRelayFilter` (gateway) |
| Resolution | Does that "who" *match a stored record*? | the Mongo `_id` (user-service) |

The first two were already working before this change. The third was not, and
§24.3 is the reason — it is the most useful thing in this section, because the
symptom was a `404` in a component that was behaving perfectly.

### 24.1 The shape — two clients, one realm

Keycloak 26.6.2, `start-dev`, realm **`ecom-app`**, Postgres-backed (the same
`postgres` container as product/order, separate database `keycloak_db`).
Published as `8443:8080` — port 8443 on the host, plain HTTP, no TLS, which is
a dev-only arrangement worth naming out loud since 8443 usually implies HTTPS.

Two clients, and the distinction between them is the thing to get straight:

| | `oauth2-pkce` | `ecom-admin` |
|---|---|---|
| kind | **public** | **confidential** (`Client authentication` On) |
| flow | authorization code + **PKCE S256** | **client credentials** |
| standard flow | On | **Off** — it is not a login client |
| service account | — | **On** |
| used by | browser / Postman, to log a *human* in | user-service, to *provision* users |
| holds a secret | no — it cannot; a browser bundle is readable | yes, `KEYCLOAK_ADMIN_CLIENT_SECRET` |

**PKCE and a client secret answer the same question** — "is the thing redeeming
this code the same thing that started the flow?" A public client cannot hide a
secret, so it proves it with PKCE instead. That makes PKCE *inert* on
`ecom-admin`: `client_credentials` has no authorization code and no redirect to
protect, and Standard flow is Off anyway. Turning it on there would change
nothing; leaving it off is not a gap.

A **service account is a real user**. Enabling "Service accounts roles" creates
a hidden user `service-account-ecom-admin`, which is why granting it permissions
uses the ordinary user role-mapping mechanism. It is visible under Users if you
search `service-account-`.

The gateway is a **resource server** and appears in neither column. It validates
tokens; it never issues them, never redirects to a login page, and holds no
credential of any kind.

### 24.2 The gateway as resource server

`gateway/security/SecurityConfig.java`. The whole chain is stateless — authority
comes from the `Authorization` header, so CSRF is disabled and there is no
session or cookie anywhere.

```yaml
# cloud-gateway.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8443/realms/ecom-app
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:8443/...certs}
```

**Those two URLs deliberately disagree at runtime**, and the reason is the same
`localhost` trap that bit the Redis host and `KAFKA_BROKERS` (§20, §23). Compose
overrides only the *key fetch* to `http://keycloak:8080/...`, because inside the
gateway container `localhost` is the gateway. The `issuer-uri` stays
`localhost:8443` because that is the string Keycloak stamps into the `iss` claim
of tokens, and validation compares against the claim, not against reachability.
One is an address the gateway dials; the other is a literal it string-matches.

Three traps, all recorded in that file's javadoc because each one costs an hour:

- **Reactive vs servlet types.** The gateway is WebFlux, so it is
  `@EnableWebFluxSecurity` / `ServerHttpSecurity` / `SecurityWebFilterChain` /
  `web.cors.reactive.*` throughout. The servlet twins have nearly identical
  names and an IDE auto-import lands on the wrong one about half the time. They
  fail differently and neither message says "you used the servlet version":
  `HttpSecurity` fails at startup with *no qualifying bean*, while the servlet
  `CorsConfigurationSource` fails on bean instantiation with
  `NoClassDefFoundError: jakarta/servlet/ServletRequest` — the class is on the
  classpath, it just cannot be *loaded* without the servlet API.
- **Actuator needs an explicit `permitAll()`.** `management.server.port` is 9090,
  a separate Netty server in a child context, which makes it look like this chain
  cannot apply. It does. Measured:
  `curl -i http://localhost:9090/actuator/health/liveness` → `401` with
  `WWW-Authenticate: Bearer`. That challenge could only have come from
  `.oauth2ResourceServer(...)`. Without the exclusion the compose healthcheck
  gets a 401, `curl -f` exits 22, and the gateway is marked unhealthy forever
  while the application is fine — the same shape of bug as §16, reached a third way.
- **CORS must run before authorization.** A preflight `OPTIONS` carries no
  `Authorization` header, so a 401 there kills the real request before it is sent.
  `.cors(Customizer.withDefaults())` inside the chain gets the ordering right.

The allowed origin is `http://localhost:5173` only. `http://localhost:8443/**`
was there previously and was wrong twice over: an origin is scheme + host + port
and never a path (the value is compared against the `Origin` header verbatim, so
a path makes it unmatchable), and 8443 is Keycloak, which is not a browser origin
that calls this API — a front end is *redirected* to Keycloak and the gateway
never sees it.

### 24.3 The bug: three identifiers, one lookup

This is the part worth reading twice.

`UserIdRelayFilter` strips any inbound `X-User-ID` and rewrites it from the
token's `sub`:

```java
headers.remove(USER_ID_HEADER);
if (!subject.isEmpty()) { headers.set(USER_ID_HEADER, subject); }
```

**Remove-then-set, in that order, is the whole point.** Setting without removing
works on the happy path and leaves the hole open on any path where no token is
present — the client's own header survives untouched. With the removal first,
the downstream header is either what the filter wrote or absent; it can never be
what the caller sent. (`defaultIfEmpty("")` is load-bearing for the same reason:
on a permitted path there is no `Authentication`, the `Mono` completes empty,
and without a default the `flatMap` never runs and the header passes through
unstripped. The empty case is exactly the case that must be handled.)

That filter was correct and the feature was still broken, because provenance is
not resolution. The lookup chain is:

```
JWT sub -> X-User-ID -> order/clients/UserLookup.getUser(userId)
        -> GET /api/users/{userId}
        -> UserController.getUserById -> userRepository.findById(id) -> Mongo _id
```

`findById` matches `_id`. It does not look at any other field. The half-landed
version of this work had introduced a **separate `keycloakId` field** on `User`
and `UserRequest`, leaving `_id` as a Mongo-generated ObjectId. So the filter
faithfully delivered a *correct* `sub` that resolved to nothing — `404` →
`UserNotFoundException` → every cart and order call failed, with no component
misbehaving.

**Therefore `_id` must equal the Keycloak `sub`.** And since user-service is now
the thing that creates the Keycloak user, the UUID it gets back *is* that `sub`,
so `keycloakId` would hold a byte-for-byte copy of `id`: two fields, one value,
and a permanent question about which is authoritative. Deleted.

```java
//User.java
//Equals the Keycloak user id, i.e. the JWT "sub" the gateway relays as X-User-ID.
@Id
private String id;
```

`UserRequest.id` went with it. It existed only to attach a profile to a
*pre-existing* Keycloak user, a case that no longer occurs — and removing it
closed a real hole, since any caller could previously POST a profile under
someone else's subject.

`UserIdRelayFilter` itself needed **no change at all**.

### 24.4 One role name, and the `ROLE_` prefix trap

The role name has to be identical in four places, so exactly one of them defines
it — the existing `UserRole` enum:

```
UserRole.CUSTOMER.name() == "CUSTOMER"          <- the enum defines it
                         == Keycloak realm role name
                         == what assignRealmRoleToUser sends
                         == realm_access.roles entry -> ROLE_CUSTOMER -> hasRole("CUSTOMER")
```

So `addUser` sends `user.getRole().name()`, never the literal `"USER"` the
half-landed version hardcoded — a fifth vocabulary that matched nothing.

**The prefix is the trap.** Spring's `hasRole("CUSTOMER")` compares against the
authority `ROLE_CUSTOMER`, and the converter prepends `ROLE_`. So the Keycloak
realm role must be named `CUSTOMER`. Naming it `ROLE_CUSTOMER` yields
`ROLE_ROLE_CUSTOMER`, which matches nothing and surfaces as a 403 — an
authorization outcome, not an error, so nothing in any log says why.

`UserMapper` keeps `@Mapping(target = "role", ignore = true)` and `UserRequest`
has no `role` field, so self-signup always produces `CUSTOMER` and **cannot
request `ADMIN`**. Promotion is a console action. The Mongo copy is
*provisioning intent, not an authorization input* — the token is authoritative at
request time, which is why drift after a console change is a cosmetic
inconsistency rather than a privilege escalation.

### 24.5 Realm roles, not client roles

The half-landed `assignRealmRoleToUser` was *named* for realm roles but its URLs
did client-role assignment (`/clients/{uid}/roles/{name}`) — which is the only
reason the property `client-uid: 7477 #fix this` existed. Moving to realm roles
makes the name true and deletes that property outright:

```
GET  /admin/realms/ecom-app/roles/{roleName}
POST /admin/realms/ecom-app/users/{userId}/role-mappings/realm   body: [ roleRep ]
```

| | realm roles | client roles |
|---|---|---|
| token claim | `realm_access.roles` | `resource_access.<clientId>.roles` |
| in the token because | a **built-in** mapper, no configuration | only when that client is in the audience |
| to assign via admin API | role name is enough | needs the client's internal **UUID** |
| fits | one application per realm | several apps sharing a realm, each with its own namespace |

This realm has one application, so realm roles. The second row is the quieter
argument: client roles have an extra way to *silently vanish* from a token.
(Keycloak's own admin API uses client roles — `realm-management` — which is
consistent with the rule, not a counterexample: that realm hosts many clients.)

### 24.6 Admin API auth — a service account, not the master admin

The half-landed code could not work: it did `grant_type=password` with
`admin-cli` against `/realms/ecom-app/`, but the bootstrap admin `admin`/`admin`
lives in the **master** realm. That returns `invalid_grant`.

Pointing the token URL at `/realms/master/` is the two-line shortcut, and it puts
the credential that controls the **entire Keycloak installation** into a
microservice's environment. The standard shape instead:

```
POST http://keycloak:8080/realms/ecom-app/protocol/openid-connect/token
  grant_type=client_credentials & client_id=... & client_secret=...
```

A dedicated confidential client whose service account holds exactly two
permissions. Both are **client roles of the auto-created `realm-management`
client** — never created by hand, and invisible in the Assign-role dialog until
the filter is switched from realm roles to **"Filter by clients"**, which is a
reliable source of confusion:

| role | needed for |
|---|---|
| `manage-users` | `POST /users`, role mappings, `DELETE /users/{id}` |
| `view-realm` | `GET /roles/{roleName}` |

A leak of this secret costs the user directory. A leak of the master admin
password costs everything.

One thing to be aware of: `user-service.yml` defaults `client-id` to
**`ecom-admin-cli`** while the client actually created in the realm is
**`ecom-admin`**. Compose supplies the real value via `KEYCLOAK_ADMIN_CLIENT_ID`,
so the stack is correct — but an IDE run without that variable set would fall
back to a client that does not exist and fail at the token call.

Client ≠ user, and secret ≠ password. Worth stating because it is the root of
most confusion here: **every** token request names a client, including
`grant_type=password`, which uses `client_id=admin-cli` *and* a
username/password. They are not alternatives.

### 24.7 Provisioning — `UserService.addUser`

Keycloak is the identity source, so it is written **first** and hands back the id:

```java
String token = keyCloakAdminService.getAdminAccessToken();
String keycloakUserId = keyCloakAdminService.createUser(token, requestUser);

try {
    User user = userMapper.toEntity(requestUser);
    user.setId(keycloakUserId);                      // _id == sub
    keyCloakAdminService.assignRealmRoleToUser(
            token, keycloakUserId, user.getRole().name());
    User savedUser = userRepository.save(user);
    return userMapper.toResponse(savedUser);
} catch (RuntimeException e) {
    try {
        keyCloakAdminService.deleteUser(token, keycloakUserId);   // compensate
    } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);             // never hide the real cause
        logger.error("Orphaned Keycloak user {} - rollback failed", keycloakUserId, cleanupFailure);
    }
    throw e;
}
```

**Keycloak cannot join a local transaction**, so the ordering creates a window:
a failed Mongo save (the unique index on `email`) would strand an account that
can log in, has no profile, and blocks re-registration with a 409 on the
username. The `catch` is a small compensating action — the saga pattern at its
smallest possible scale. `addSuppressed` rather than swallowing matters: a
rollback failure must never replace the reason the request actually failed.

The id comes out of the `Location` header, because a Keycloak `201` has no body:

```java
//201 carries no body; the new id is only in the Location header.
URI location = response.getHeaders().getLocation();
String path = location.getPath();
return path.substring(path.lastIndexOf('/') + 1);
```

**Signup must be public**, and matched on method + path:

```java
.pathMatchers(HttpMethod.POST, "/api/users").permitAll()
```

Requiring a token on the endpoint that *creates the account* is circular — no
account, no token, no way to make an account. Matching the method as well keeps
`GET /api/users` (list every user) behind authentication.

### 24.8 Six bugs that each independently stopped signup working

Worth listing because they are all of a kind: quiet, and none of them produce a
message naming the cause.

| bug | what actually happened |
|---|---|
| `http://localhost:8443` hardcoded in `KeyCloakAdminService` | inside the container `localhost` **is** user-service — connection refused. Every URL now comes from `KeyCloakAdminProperties.getServerUrl()` |
| `@JsonIgnore` on `UserRequest.password` | it blocks **deserialization** too, so `getPassword()` was always null and Keycloak received `credentials: [{value: null}]`. Now `@JsonProperty(access = WRITE_ONLY)` — accepted inbound, never echoed outbound |
| `keycloak.admin.*` lived in **`cloud-gateway.yml`** | nothing in the gateway binds it; a resource server has no business holding admin credentials. Moved to `user-service.yml` |
| role mapping posted a bare object | the endpoint takes `List<RoleRepresentation>` → `400`. Now `.body(List.of(roleRep))` |
| converter read `resource_access` unguarded | `getClaimAsMap` returns null when the claim is absent → NPE → **500 on every request** from a role-less user, when the right outcome is authentication with zero authorities. Both steps are now null-guarded |
| `httpclient5` undeclared in `user/pom.xml` | `RestClientConfig` uses `HttpComponentsClientHttpRequestFactory`, which resolved only *transitively* via the Eureka client — one dependency change away from breaking. Now declared explicitly |

Plus one that is not a bug but cost the most time historically: **error bodies
were being discarded**. `defaultStatusHandler(..., statusText)` reduced
Keycloak's `{"errorMessage":"User exists with same email"}` to `Client error:
Conflict`. The Keycloak client now gets its own `@Qualifier`'d `RestClient` bean
(throw-on-error is right for the admin API and wrong to impose on every other
caller) whose handler includes method, URI, status **and body**:

```java
throw new KeycloakAdminException(
    "Keycloak " + request.getMethod() + " " + request.getURI()
        + " -> " + response.getStatusCode() + " " + readBody(response.getBody()));
```

user-service also gained its first `@RestControllerAdvice`: `DuplicateKeyException`
→ **409**, `KeycloakAdminException` → **502**. The 502 is deliberate — the failure
is in Keycloak, not in this service, and the status should say so.

### 24.9 `KC_HOSTNAME` — hostname v2, and why it is not cosmetic

```yaml
KC_HOSTNAME: http://localhost:8443     # was: KC_HOSTNAME: localhost + KC_HOSTNAME_PORT: 8080
```

The old pair is hostname **v1**, which Keycloak 26 warns about, and it named the
wrong port besides. More importantly, with it the issuer depended on which `Host`
header arrived — so a token fetched from the host and a token fetched from inside
`ecom-network` could carry different `iss` values, and the gateway validates that
claim as a literal. Pinning one full URL makes Keycloak stamp
`iss=http://localhost:8443/realms/ecom-app` either way.

It does **not** change the bind address. `keycloak:8080` still serves in-network,
which is what `KEYCLOAK_SERVER_URL` and the gateway's `jwk-set-uri` use. Hostname
controls *generated* URLs, not where the server listens.

### 24.10 Verification — measured, end to end

Realm state had to be corrected first: `CUSTOMER` and `ADMIN` **did not exist**
as realm roles (the realm had only `default-roles-ecom-app`, `offline_access`,
`uma_authorization`), and `getRealmRoleRepresentation` does a `GET` that 404s on
a missing role. Both were created via the admin API.

| check | result |
|---|---|
| `POST /api/users`, **no token** | **201** |
| returned `id` | `def64c9c-a40f-4bc5-b539-7b8f5adf2384` — a **UUID**, not a 24-char ObjectId |
| Keycloak user at that exact id | exists, username `user1787105358` |
| its realm roles | **`CUSTOMER`** + `default-roles-ecom-app` |
| response body | `role: "CUSTOMER"`, **no `keycloakId` field** |
| `GET /api/users/{sub}` in-network | **200** — the Mongo `_id` resolves. *This is the proof of §24.3* |
| token `iss` | `http://localhost:8443/realms/ecom-app` — matches `issuer-uri` |
| token `sub` | identical to the Mongo `_id` and the Keycloak id |
| token `realm_access.roles` | `[CUSTOMER, default-roles-ecom-app, offline_access, uma_authorization]` |
| gateway converter log | `Extracted roles for sub def64c9c-…: [… CUSTOMER …]` |
| `GET /api/products` with token | **200** |
| `POST /api/carts` with token, **no** `X-User-ID` | **201** — the gateway injected it |
| `GET /api/carts` with forged `X-User-ID: attacker-id` | returned the **token user's** cart, `userId: def64c9c-…` |
| `GET /api/users`, `GET /api/users/{id}`, `GET /api/products` — no token | **401** each |
| gateway JWKS / decoder errors | none |
| hostname v1 deprecation warning | gone |

The forgery row is the one that matters: an item was placed in the cart, then read
back with `X-User-ID: attacker-id` attached. The response carried the token's
subject. The header was discarded and rewritten, which is §24.3's remove-then-set
working under adversarial input rather than in theory.

The first signup attempt returned **502** carrying
`{"errorMessage":"User exists with same email"}`. Not a defect — it proved the
whole chain (service-account token → Admin API) and showed the new error handler
doing its job. The old handler would have said `Client error: Conflict`.

### 24.11 Adding more roles — five mechanisms, and which one scales

§24.4 fixes the *name* of one role. It does not say how a second role ever gets
onto a user. There are five ways, and they differ in where the decision lives.

| | mechanism | who decides | code needed |
|---|---|---|---|
| A | Keycloak console → user → Role mapping | a human admin | **none** |
| B | admin-only `POST /api/users/{id}/roles` | your API, gated `hasRole("ADMIN")` | a controller method |
| C | `role` field on `UserRequest` | the person signing up | a field — and an allow-list |
| D | **default roles** and **groups** | the realm, automatically | none, or less |
| E | composite roles | the role definition itself | none |

**A works today, with nothing added.** Realm roles → Create role → assign it to a
user. The token carries it on the next login, the converter turns it into
`ROLE_<NAME>`, and any `hasRole(...)` rule sees it. The Mongo `role` column goes
stale and that is fine — §24.4 already establishes it as provisioning intent, not
an authorization input.

The one thing worth naming about A: **the first `ADMIN` has to be console-made.**
There is no bootstrap path through the API, for the same circular reason signup
had to be `permitAll()` — an admin-only endpoint cannot be used to create the
first admin.

**B is the real API answer**, and it is nearly free because
`assignRealmRoleToUser` already exists and takes a role name:

```
POST /api/users/{id}/roles   { "role": "ADMIN" }     <- hasRole("ADMIN")
```

It is **blocked on the gateway actually enforcing roles**. Under
`.anyExchange().authenticated()` a `hasRole("ADMIN")` rule at the gateway does not
exist, and user-service has no security on the classpath at all, so the endpoint
would be reachable by any authenticated user — a self-service promotion endpoint.
Order of work: tighten the gateway first, add the endpoint second.

**C is the one to be careful with.** Letting the request name its own role is a
privilege-escalation hole in a single line — `{"role":"ADMIN"}` and the caller is
an admin. It is defensible only with a server-side allow-list of self-selectable
roles (`CUSTOMER`, maybe `SELLER` pending approval), and never as a bare
pass-through. This is exactly why `UserRequest` has no `role` field today.

**D is what actually scales.** Two Keycloak features, neither of which needs code:

- **Default roles.** The realm already has a composite `default-roles-ecom-app`
  granted to every new user (it is in the log line in §24.10, alongside
  `offline_access` and `uma_authorization`). Adding `CUSTOMER` to it makes every
  signup a customer automatically — which would **delete the
  `assignRealmRoleToUser` call from `addUser` entirely**, along with its failure
  mode. The trade: role assignment becomes invisible in your code and lives only
  in realm config, which is a real cost when someone asks six months later why
  new users are customers.
- **Groups.** A group carries a set of roles; membership is one call:
  `PUT /admin/realms/{realm}/users/{id}/groups/{groupId}`. This is the standard
  answer once there is more than one role per user, because it moves the
  role *composition* into Keycloak and leaves your service assigning exactly one
  thing. Moving a user from `/staff/support` to `/staff/managers` changes several
  roles at once with no deployment.

**E, composite roles**, is the same idea one level down: `MANAGER` can *include*
`SUPPORT` and `CUSTOMER`, so the token carries all three and the rules stay
simple. Groups compose *users*; composites compose *roles*.

#### What a real e-commerce set looks like

| role | what it is allowed to do | assigned by |
|---|---|---|
| `CUSTOMER` | own cart, own orders, own profile | signup — default role (D) |
| `SELLER` / `VENDOR` | create and update **their own** products | admin approval (B) |
| `SUPPORT` | read any order, issue refunds; no product writes | group membership (D) |
| `ADMIN` | everything, including role assignment | console (A) |

The rule that keeps this from rotting:

> **Roles answer "what *kind* of user is this". They never answer "does this
> object belong to this user".**

`SELLER` says a caller may edit products. It cannot say *which* products — that is
a comparison between `X-User-ID` and the row's owner, and it belongs in the
service, next to the data, where the owner field actually is. The moment a role is
invented to express ownership (`SELLER_42`) the model has gone wrong: roles are
enumerated at design time, rows are not.

That splits enforcement cleanly in two:

```
gateway   coarse   "is this caller a SELLER at all?"        hasRole("SELLER")
service   fine     "is product 42 owned by this X-User-ID?" a field comparison
```

The gateway rejects the whole category cheaply; the service does the row-level
check it is the only component able to do. Neither can do the other's job.

#### One consequence for `UserRole`

`UserRole` being a Java enum is right *today*, when the two names are fixed and
the service assigns one of them. It becomes a liability the moment roles are meant
to be added from the Keycloak console without a deployment — a console-created
`SUPPORT` role has no enum constant, and `UserRole.valueOf("SUPPORT")` throws.
The end state that follows from §24.4's "the token is authoritative" is to drop
`User.role` altogether and read roles only from the token. The Mongo copy is
already not consulted for any decision; deleting it removes the drift rather than
documenting it.

### 24.12 Why not keep both ids — `_id` *and* `keycloakId`

§24.3 says `keycloakId` was deleted. The fair question is why they cannot simply
coexist: let Mongo generate `_id`, store the Keycloak UUID alongside it, expose
both on `UserResponse`. Nothing forbids it. The question is what the second id
*buys*, and here the answer is nothing, at a cost that keeps being paid.

**Four things the two-id design requires**, none optional:

1. **`findById` stops covering the primary access path.** Every downstream lookup
   starts from the token's `sub`, so `UserRepository` needs `findByKeycloakId`,
   `UserController` needs to route to it, and `UserServiceClient` in order-service
   needs to call whichever endpoint that is. The repository today is a bare
   `MongoRepository<User, String>` with no derived queries at all — this is the
   first thing that would be added, and it exists only to undo the mismatch.
2. **`keycloakId` needs `@Indexed(unique = true)`.** Without it two profiles can
   claim the same Keycloak subject, and *which one* answers a lookup is
   unspecified. `_id` gets that guarantee for free; the second id has to buy it.
3. **`CartItem.userId` and `Order.userId` must pick one.** Store the `sub` and
   Mongo's `_id` has no readers outside user-service — a private key nobody uses.
   Store `_id` and every cart request needs a `sub` → `_id` translation *on the hot
   path*, which is an extra user-service call per request.
4. **`UserResponse` leaks both**, so every client has to know which one to send
   back. That is the cost that actually bites.

The general principle, which is not Keycloak-specific:

> A local id earns its place only if the local record can exist **independently**
> of the external one.

Two tests. *Can a `User` exist with no Keycloak account?* No — `addUser` creates
the Keycloak account first and refuses to write a profile without one. *Can one
`User` have several identities?* No — one subject, one profile. Both structurally
"no", so the second id is a synonym.

**The cost is ambiguity at every future call site.** Both fields are `String`, so
passing the wrong one **compiles**. It does not fail loudly either: it returns a
`404`, or worse an empty cart, because the id was well-formed and simply matched
nothing. That is the same shape of bug as §24.3, permanently re-armed. With
`_id == sub` the mismatch is not caught — it is **unrepresentable**. An unlinked
profile cannot be created, because there is no field to disagree with.

#### Where two ids *is* correct

Three cases, all real:

- **Retrofitting an IdP onto an existing system.** Documents already have
  ObjectIds that orders and carts reference. Mongo's `_id` is **immutable** — it
  cannot be rewritten, only deleted and reinserted — so a separate `keycloakId` is
  the only way to link the two key spaces without rewriting history. This is
  probably why the half-landed version had one; it is the right answer to a
  different question.
- **Multiple identity providers.** Google, Apple and Keycloak logins converging on
  one profile means several external subjects against one local record — the "can
  one User have several identities?" test flips to yes, and the local id is
  genuinely primary.
- **The profile must outlive the identity.** Retention and GDPR flows sometimes
  delete the login while keeping order history attached to a profile stub.

#### And what the single-id choice costs

Honest counterweight, so this is a trade rather than a rule:

- **Key-space coupling to Keycloak.** Migrating identity providers means rewriting
  user ids everywhere they are referenced — carts, orders, logs.
- **Delete-and-recreate breaks the link.** A user deleted in the console and made
  again gets a *new* UUID; the old profile is orphaned. With `keycloakId` it would
  be one field to update.
- **Index locality.** A 36-character UUID string is a larger, less local `_id` than
  a 12-byte ObjectId. Immaterial at this size; worth knowing it is not free.

The trade is taken deliberately: this system is new, has one IdP, and creates
every account through the same code path.

### 24.13 Not verified, and what is left

**The compensating delete has never fired.** It could not be triggered naturally:
Keycloak rejects a duplicate email itself, so the request never reaches Mongo and
the "Keycloak succeeded, Mongo failed" state does not occur. Architecturally that
is good — Keycloak is the first gate — but the `catch` block in §24.7 is written
and compiled and untested. Exercising it needs the realm's *Duplicate emails
allowed* setting flipped.

Remaining:

- **Roles are extracted but not enforced.** Everything is still
  `.anyExchange().authenticated()`. That was deliberate staging: a converter bug
  under `hasRole(...)` would 403 every route at once and look like a broken
  gateway rather than a missing claim. The log line above is the evidence the
  claim arrives, so tightening to per-route `hasRole(...)` is now unblocked — and
  the converter's `logger.info` should drop to `debug` at the same time, since it
  logs on every request.
- Keycloak has **no `healthcheck:`** while every other service has one.
  `KC_HEALTH_ENABLED` is already true and exposes `/health/ready` on management
  port 9000. `depends_on: service_started` is sufficient today only because the
  admin token is fetched lazily on the first signup, not at startup.
- **React**, when it arrives, is a *public* client and reuses `oauth2-pkce` —
  same category as Postman. It must never receive the admin secret; a bundle is
  readable in devtools, build-time `VITE_*` variables included. The React-only
  gotcha is Keycloak's **Web Origins** setting, which is separate from redirect
  URIs: Postman is a desktop app and bypasses browser CORS entirely, so this is
  the one thing that works in Postman and then fails in a browser.
- The `.env` entry `KEYCLOAK_ADMIN_CLIENT_SECRET` is the only new secret; it is
  referenced from `docker-compose.yml` and never committed.

## 25. Outbound HTTP — two client shapes, and which one to reach for

Every other section in this document is a change log: something was broken or
missing, here is what was done. This one is not. Nothing here was changed except
§25.9 at the end — the rest is an attempt to answer a question the change log
cannot, because the answer is spread across §8, §17, §18 and §24:

> **When this project needs to call something over HTTP, which of the two
> completely different client setups is the right one, and why are there two?**

Both shapes already exist in the repository, in two classes with **the same name
in different packages**, which is itself a source of confusion worth clearing up:

```
order/src/main/java/com/ramesh/order/clients/RestClientConfig.java   <- shape B
user/src/main/java/com/ramesh/user/config/RestClientConfig.java      <- shape A
```

### 25.1 The question that starts it: why not `RestClient.create()`?

`RestClient.create()` is one line and it works. The Keycloak client is thirty
lines in a `@Configuration` class. The honest question is what those extra
twenty-nine lines buy, because "more configuration" is not a virtue.

They buy exactly two things, and each one maps to a specific failure that
would otherwise happen:

| what it adds | the failure it prevents |
|---|---|
| a status handler that includes the response **body** | a `409` that says only `"Client error: Conflict"` |
| a request factory with a **read timeout** | one slow Keycloak parking a request thread indefinitely |

Everything else — message converters, JSON handling — `RestClient.create()`
already does. If neither of those two failures applied, the one-liner would be
the right call. This is the test to apply before writing a client config: *name
the failure*. If you cannot, delete the config.

### 25.2 What it buys, 1 — the body is the diagnosis

```java
.defaultStatusHandler(HttpStatusCode::isError,
        (request, response) -> {
            throw new KeycloakAdminException(
                    "Keycloak " + request.getMethod() + " " + request.getURI()
                            + " -> " + response.getStatusCode() + " "
                            + readBody(response.getBody()));
        })
```

Keycloak puts the reason for a failure in the **body**, not the status line.
Register a username that already exists and it answers:

```
409 Conflict
{"errorMessage":"User exists with same username"}
```

`RestClient`'s default 4xx behaviour throws `HttpClientErrorException`, whose
message is built from the status and reason phrase — the body is dropped. So
without this handler the log reads `Client error: Conflict` and says nothing
about *what* conflicted. With it, the log carries the method, the URL, the
status and Keycloak's own sentence.

This is not a nicety. §24.8 lists six independent bugs that each stopped signup
working, and their symptoms were `invalid_client`, `invalid_grant`, a `400` on
the role-mapping call and a `404` on the role lookup — **four different causes
that all present as a bare 4xx**. Told apart only by the body. The difference
between a five-minute diagnosis and a two-hour one is this one lambda.

The same idea generalises: an exception that says `database error` versus one
that says `duplicate key violation on users.email`. Same failure, different
afternoon.

### 25.3 What it buys, 2 — the timeout, and what it actually covers

```java
HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
factory.setConnectionRequestTimeout(5000);
factory.setReadTimeout(5000);
```

The rule behind this is the one worth carrying to every project:

> **Every synchronous call that leaves the JVM needs a timeout.**

Note the boundary is the **process**, not the network. A local helper method
cannot hang on a network because there is no network in it. The moment a call
leaves the JVM — even to another container on the same Docker bridge, even to
`localhost` — it can hang, and an unbounded wait turns *"Keycloak is slow"* into
*"user-service is down"*: `addUser` blocks, every concurrent signup blocks behind
it, and the servlet thread pool drains. A dependency being slow should cost you
one failed request, not the service.

**But be precise about which timeout is which**, because the naming misleads:

| setter | what it actually bounds |
|---|---|
| `setConnectionRequestTimeout` | waiting to **lease a connection from the pool** — not the TCP handshake |
| `setReadTimeout` | waiting for response bytes once the request is sent |

There is no third setter. Spring Framework 7's
`HttpComponentsClientHttpRequestFactory` exposes only these two — verified with
`javap` against `spring-web-7.0.8.jar`:

```
public void setConnectionRequestTimeout(int);
public void setReadTimeout(int);
```

So the **TCP connect timeout is not set here at all**; it stays at HttpClient 5's
`ConnectionConfig` default, which is 3 minutes. In practice this rarely bites:
if the Keycloak container is down, the connection is *refused* immediately and
the call fails fast. It bites when packets are silently dropped rather than
refused — a network partition or a firewall — where the call can hang far longer
than the "5s" in the code suggests. Worth knowing before trusting that number.

### 25.4 The reusability boundary — is this a general-purpose client?

The natural next thought is: this bean has a timeout and good errors, so should
every outbound call in user-service use it? No — and the two customisations split
in opposite directions.

- **The timeout half is universal.** Any across-process call wants one.
- **The error-handler half is Keycloak-specific and would be actively wrong
  elsewhere.** It throws on *every* non-2xx. A `404` from product-service
  (`GET /api/products/999`) is a normal, expected outcome that a caller wants to
  turn into an `Optional.empty()` — not an exception. And the exception is named
  `KeycloakAdminException`; throwing that from a product lookup is a lie in the
  stack trace.

Which is why it is a **named** bean rather than the default one:

```java
@Bean("keycloakRestClient")
public RestClient keycloakRestClient() { ... }
```

The name is a signal — *this is not the general-purpose client, do not pick it up
by accident.* And Spring honours that literally: a named bean is injected only
where something asks for it by name. In this module there is exactly one such
place, `KeyCloakAdminService`'s constructor:

```java
public KeyCloakAdminService(KeyCloakAdminProperties adminProperties,
                            @Qualifier("keycloakRestClient") RestClient restClient) {
```

So the answer to "will this be used everywhere inside user-service?" is **no, and
it cannot be by accident**. Nothing else injects a `RestClient` in the module. If
user-service ever needed to call another service over HTTP, it would declare a
*second* bean with its own name — same timeout reasoning, different error policy.

> **One configured client per external dependency**, each tuned to that
> dependency's quirks. Not one shared client for everything, and not a fresh
> `RestClient.create()` at each call site.

### 25.5 The other shape — and why it looks nothing like this one

order-service also calls over HTTP, and its `clients/RestClientConfig` shares
almost nothing with the Keycloak one. That is not inconsistency; the two are
answering different questions.

| | **Shape A** `user/config/` | **Shape B** `order/clients/` |
|---|---|---|
| target | Keycloak | product-service, user-service |
| is the target in Eureka? | **no** — third-party, fixed URL | **yes** — registered, possibly many instances |
| what the bean is | one finished `RestClient` | two `RestClient.Builder`s |
| bean selection | `@Bean("keycloakRestClient")` + `@Qualifier` | `@Primary` vs `@LoadBalanced` |
| URL written in code | `http://keycloak:8080` from properties | `http://product-service` — a service **id**, no host, no port |
| who resolves the host | nobody; it is the real host | Spring Cloud LoadBalancer, via Eureka, per request |
| call style | hand-written `restClient.post().uri(...)` | declarative `@HttpExchange` interface |
| error policy | throw on any non-2xx, body included | Boot defaults; failures feed `@CircuitBreaker` |
| timeout | 5s read (§25.3) | added in §25.9 — there was none |

The `@HttpExchange` half of shape B is worth naming as its own idea. Instead of
writing the call, you declare it:

```java
@HttpExchange
public interface ProductServiceClient {
    @GetExchange("/api/products/{id}")
    ResponseEntity<ProductResponse> getProductById(@PathVariable("id") Long id);
}
```

and `HttpServiceProxyFactory` generates the implementation. Same family as Feign,
but built into Spring Framework with no extra dependency and no annotation
processor. For repeated calls to the same service this beats hand-writing
`restClient.get().uri(...).retrieve()` at every call site — the URL template lives
in one place and the compiler checks the argument types.

Why shape B needs **two** builders rather than one is a separate and non-obvious
story — it is about Eureka's own internal HTTP transport accidentally being
load-balanced and breaking registration. That is §17; it is not repeated here.

### 25.6 The decision rule

Collapsing the table into something usable:

```
Is the thing I am calling registered in Eureka?

  yes ──> shape B: @LoadBalanced builder + @HttpExchange interface
          call it by service id, let discovery pick an instance
          lenient errors - a 404 is data, not an exception

  no  ──> shape A: a named, single-purpose RestClient
          real URL from configuration, never hardcoded
          error policy tuned to that dependency's body format
```

Both shapes get a timeout. That part is not a choice.

Two footnotes on the rule. First, "not in Eureka" is the common case for anything
you did not write: Keycloak, a payment gateway, an email provider. Second, the
question is *discovery*, not *trust* — an internal service reached by a fixed URL
(no Eureka) is still shape A.

### 25.7 Three layers, and one reason to change each

The chain `RestClientConfig → KeyCloakAdminService → UserService` is three
classes deep for a signup, which looks like ceremony until you ask what each
layer would change *for*:

| layer | owns | changes when |
|---|---|---|
| `RestClientConfig` | **how** we speak HTTP to Keycloak — timeouts, error translation | Keycloak's error format changes, or a timeout needs tuning |
| `KeyCloakAdminService` | **what** Keycloak operations exist — create, assign role, delete | a new admin operation is needed |
| `UserService` | **the business rule** — the signup orchestration and its undo | the signup policy changes |

Each has a single reason to change, which is the useful reading of the Single
Responsibility Principle — not "one class does one thing" but "one class answers
to one stakeholder". Note also that `KeyCloakAdminService` knows nothing about
signup: it is a thin wrapper over Keycloak's admin API and would be unchanged if
`addUser` were rewritten completely.

Collapsing all three into a `RestClient.create()` inside `UserService` would lose
the error diagnosis, lose the timeout, and weld business logic to HTTP plumbing —
three costs, no saving.

### 25.8 The compensating transaction, in the order the code actually does it

`UserService.addUser` is the interesting method in the module, because it spans
two systems that cannot share a transaction. Keycloak is a separate process over
HTTP; `@Transactional` has no reach into it. The code compensates by hand:

```java
String token          = keyCloakAdminService.getAdminAccessToken();
String keycloakUserId = keyCloakAdminService.createUser(token, requestUser);   // (1)

try {
    User user = userMapper.toEntity(requestUser);
    user.setId(keycloakUserId);                                                // _id == sub, §24.3
    keyCloakAdminService.assignRealmRoleToUser(                                // (2)
            token, keycloakUserId, user.getRole().name());
    User savedUser = userRepository.save(user);                                // (3)
    return userMapper.toResponse(savedUser);
} catch (RuntimeException e) {
    try {
        keyCloakAdminService.deleteUser(token, keycloakUserId);                // undo (1)
    } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
        logger.error("Orphaned Keycloak user {} - rollback failed", keycloakUserId, cleanupFailure);
    }
    throw e;
}
```

The ordering detail that a casual reading misses: **the `try` covers steps 2 and
3, not just the save.** Role assignment happens *between* the Keycloak account
being created and the Mongo document being written, so a failed role assignment
triggers the same compensating delete. That is correct — a user who exists in
Keycloak with no role and no profile is exactly as broken as one with no profile.

Three outcomes, and it is worth being able to state all three:

| what fails | Keycloak ends | Mongo ends | net |
|---|---|---|---|
| nothing | user + role | document | consistent |
| step 2 or 3, delete succeeds | **empty** | empty | consistent — back to pre-signup, the caller can retry |
| step 2 or 3, **delete also fails** | orphaned account | empty | **inconsistent** — logged loudly at ERROR |

The third row is why `deleteUser` failing does not swallow the original
exception: `e.addSuppressed(cleanupFailure)` attaches the cleanup failure to the
real one, so the stack trace shows both, and the *original* cause is still what
propagates. Losing the real error to report a failed rollback is a classic way to
make an incident unreadable.

This is the **Saga** pattern at its smallest — when one atomic transaction across
two systems is impossible, do the steps in order and write an explicit undo for
each. §21–§23 hit the same family of problem from the messaging side, where the
undo is not available at all and the answer is an outbox instead.

Two limits of this implementation, neither currently a bug:

- The orphan window is real but small. Between (1) and (3) an account exists that
  can log in and has no profile. Any request it makes 404s in user-service.
- `getAdminAccessToken()` runs on **every** signup — no caching. The token is
  valid for minutes; this is one extra round trip per registration. Harmless at
  this volume, and the first thing to cache if signup ever gets hot.

### 25.9 The gap this section found, and the fix

Writing §25.3 exposed that the rule *"every synchronous call that leaves the JVM
needs a timeout"* had been applied to Keycloak and **not** to the two internal
clients. order-service called product-service and user-service with no timeout at
all, and the circuit breaker did not supply one. `order-service.yml` says so in
its own comment:

```
# slow-call-duration-threshold: 4s
# ... Note it records, it does not abort - the caller still waits.
```

That is the trap. A `@CircuitBreaker` looks like protection against a hanging
dependency and is not: `slow-call-duration-threshold` classifies a finished call
as slow *after the fact* so the breaker can open on the pattern. It never
interrupts the call in flight. The `@CircuitBreaker` **annotation** applies no
`TimeLimiter`. So the breaker would eventually open — after five calls, each of
which had already parked a thread indefinitely.

Fixed in `config/order-service.yml`:

```yaml
spring:
  http:
    clients:
      connect-timeout: 2s
      read-timeout: 5s
```

Why these numbers:

- **`read-timeout: 5s`** sits *above* the 4s `slow-call-duration-threshold`, so
  slow calls are still recorded as slow and the breaker keeps opening on the
  pattern it was tuned for. Setting it below 4s would abort calls before they
  could ever be classed slow, silently turning `slow-call-rate-threshold` into
  dead config. It is also no higher than the gateway's 5s `TimeLimiter` — past
  that point the gateway has already given up and the answer has nowhere to go.
- **`connect-timeout: 2s`** matches the `rabbitmq.connection-timeout: 2s`
  directly above it, for the same reason given there.

One naming trap, verified against `spring-boot-http-client-4.1.0.jar`'s
configuration metadata: **`spring.http.client.*` is deprecated since Boot 4.0.0**
in favour of **`spring.http.clientS.*`** — plural. Both still bind in 4.1, so the
singular form works and quietly logs a deprecation rather than failing, which is
the worst kind of typo to have.

These properties reach the `@HttpExchange` clients because order's
`RestClientConfig` builds both builders through
`restClientBuilderConfigurer.configure(RestClient.builder())` rather than a bare
`RestClient.builder()` — Boot's configurer is what applies the settings derived
from `spring.http.clients.*` (§8 makes the same point about message converters).
The Keycloak client is deliberately unaffected: it sets `.requestFactory(...)`
explicitly and so overrides Boot's settings, which is the correct behaviour for a
client that wants its own policy.

**Not verified.** The timeouts are configured but not yet exercised — no service
was running when this was written. The test is to pause product-service
(`docker pause ecom_product_service`, which drops packets rather than refusing
them) and confirm the order call fails at ~5s instead of hanging. `docker stop`
will *not* demonstrate it: a stopped container refuses the connection instantly
and the call fails fast with or without the timeout.


## 26. The React front end — PKCE in a browser, and what the backend owed it

The first client of this system that is not Postman. That distinction is the whole
section: **Postman is a desktop app and has no origin**, so it bypasses the
same-origin policy entirely. Every browser-specific mechanism — CORS preflights,
Keycloak's Web origins, the redirect round trip — was therefore unproven, no
matter how green the Postman runs looked.

Stack: Vite 8, React 19, `react-oauth2-code-pkce`, Tailwind v4 via
`@tailwindcss/vite`, `react-router-dom` 7, axios. It lives in `frontend/` and runs
on the Vite dev server only — deliberately **not** a compose service, because the
origin is the thing that matters and `npm run dev` already serves it on the origin
the gateway trusts.

### 26.1 The two fields that are not the same field

Keycloak has *two* separate allow-lists and they answer different questions:

| field | checked by | question |
|---|---|---|
| Valid redirect URIs | the **authorization** endpoint | may I send the user back here with a code? |
| **Web origins** | the **token** endpoint | may a browser at this origin read my response? |

Web origins exists only to put `Access-Control-Allow-Origin` on the token
response. Postman never needed it, so it was never set.

The failure it produces is genuinely misleading, because every *visible* step
works: the login page appears, the password is accepted, the browser lands back
on `http://localhost:5173/?code=…`. Then the silent background `POST` to
`/protocol/openid-connect/token` is blocked by the browser and the app simply
never receives a token. Nothing in the Keycloak log, nothing in the gateway log —
the request never reached either.

Format traps, both of which mirror problems already in this document:

- Redirect URI needs the wildcard: `http://localhost:5173/*`.
- Web origins must **not** have one: `http://localhost:5173`, no trailing slash.
  It is compared against the `Origin` header verbatim — the identical mistake
  §15 records for the gateway's own CORS bean, where `http://localhost:8443/**`
  was wrong twice over, being both a path and the wrong host.

The gateway side needed **no change at all**. `SecurityConfig.corsConfigurationSource`
already allowed exactly `http://localhost:5173` with every method. Verified
directly rather than assumed:

```
OPTIONS /api/products   Origin: http://localhost:5173   -> 200
    Access-Control-Allow-Origin:  http://localhost:5173
    Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
    Access-Control-Allow-Headers: authorization, content-type

OPTIONS /api/products   Origin: http://evil.example      -> 403
```

That second line is the useful one: a wrong origin is rejected at the gateway, so
a CORS failure in the browser can be attributed to Keycloak rather than here.

### 26.2 What the backend owed the front end

Two gaps, both found by asking what a browser client would actually need.

**`GET /api/orders` did not exist.** `OrderController` had only `@PostMapping`, so
an order could be placed and never seen again. Three small additions:

```java
// OrderRepository
@EntityGraph(attributePaths = "items")
List<Order> findByUserIdOrderByCreatedAtDesc(String userId);

// OrderController
@GetMapping
public ResponseEntity<List<OrderResponse>> getUserOrders(
        @RequestHeader("X-User-ID") String userId)
```

Three details carry the design:

- **The `@EntityGraph` is not decoration.** `Order.items` is `@OneToMany` and so
  LAZY; without it this is N+1, one extra `SELECT` per order. Confirmed from the
  `show-sql: true` output — a single statement with a join:
  ```sql
  select o1_0.id, …, i1_0.product_id, … from orders o1_0
  left join order_item i1_0 on o1_0.id=i1_0.order_id
  where o1_0.user_id=? order by o1_0.created_at desc
  ```
- **`getUserOrders` takes no order id.** The only input is a header the gateway
  overwrites from the token's `sub`, so a caller cannot request another user's
  orders even deliberately. Authorization here is *structural* rather than a
  check that could be forgotten — the same shape as the cart endpoints.
- **Empty history is `200 []`, not `404`.** "You have never ordered" is a
  successful answer to a valid question. A 404 would push the front end into
  treating a normal state as an error.

**A malformed `productId` returned 500.** `CartService.addToCart` parses with
`Long.valueOf` and its comment states the intent plainly — *"a malformed id is a
bad request, not a product-service failure"* — but `GlobalExceptionHandler` had no
`NumberFormatException` entry, so it fell through to Spring's default. The intent
was true in the code and false over HTTP.

That distinction is invisible to Postman and load-bearing for a browser client: a
`500` tells a client *the server broke, retry*, when the truth is *your payload is
junk, retrying will never work*. Now:

```
POST /api/carts  {"productId":"abc"}
  -> 400 {"error":"INVALID_PRODUCT_ID","message":"productId must be numeric"}
```

### 26.3 One axios instance, and an ordering bug avoided

The reference app (`../pkce-flow/pkce-demo-react`) creates its client inside a
hook:

```js
export const useApi = () => {
  const { token } = useAuthContext();
  return axios.create({ headers: { Authorization: `Bearer ${token}` } });  // every render
};
```

Two defects that a demo never feels. A **new object identity on every render**, so
the client can never appear in a `useEffect` dependency array without looping. And
an `Authorization` header **frozen at creation**, so it is stale the moment the
token refreshes.

Replaced with one module-level instance whose interceptor reads the current token
at *request* time:

```js
export const api = axios.create({ baseURL: 'http://localhost:8080' });
let currentToken = null;
export const setAuthToken = (t) => { currentToken = t; };
api.interceptors.request.use((cfg) => {
  if (currentToken) cfg.headers.Authorization = `Bearer ${currentToken}`;
  return cfg;
});
```

**The non-obvious part is where `setAuthToken` is called.** The natural instinct
is an effect:

```js
useEffect(() => setAuthToken(token), [token]);   // WRONG here
```

**React runs effects child-first.** `CartProvider` sits below the component that
would own that effect, so the cart's first fetch fires *before* the parent effect
installs the token — and every page load opens with a 401 that disappears on the
next interaction. Rendering, unlike effects, is top-down, so the assignment is
done during render instead:

```js
function AuthSync({ children }) {
  const { token } = useAuthContext();
  setAuthToken(token);        // during render: parents render before children fetch
  …
}
```

A side effect during render is normally a smell. It is defensible here precisely
because it is **idempotent** — assigning the same string twice is indistinguishable
from assigning it once, so a double render in StrictMode changes nothing.

The response interceptor is where `401` and `429` are handled once instead of in
every page. The `429` branch carries a note worth repeating: the gateway
rate-limits `/api/products/**` and `/api/users/**`, and **React StrictMode
double-invokes effects in development**, so every catalogue mount is two requests.
A 429 while developing is usually that, not a real flood.

### 26.4 Context layout, and what is deliberately not in context

| provider | owns | why not local state |
|---|---|---|
| `AuthProvider` (library) | token, tokenData, login, logOut | given |
| `CartProvider` | items, count, total, add/remove/refresh | the NavBar badge and the Cart page must never disagree |
| `ToastProvider` | transient errors | any layer can fail; one place renders it |

There is **no ProductProvider**. Products are page-scoped with no second consumer,
so putting them in context would buy cache-invalidation work and nothing else.

`CartProvider` re-fetches after every mutation rather than updating locally. That
is forced by the API, not chosen: `POST /api/carts` returns
`ResponseEntity<Void>`, so there is no representation of the new state to merge —
the server is the only thing that knows the answer.

### 26.5 Three contract traps the UI has to respect

All three are places where the type system stops helping at the HTTP boundary.

1. **`productId` is a `String` in the cart DTOs and a `Long` on the product.**
   `CartItemRequest { String productId; }` versus `ProductResponse { Long id; }`.
   In Java that mismatch is caught; in JavaScript both are just values, so the
   client stringifies at the one place it can go wrong:
   ```js
   api.post('/api/carts', { productId: String(productId), quantity })
   ```
   The backend inconsistency is deeper than the edge — `CartItem.productId` is a
   `String` while `OrderItem.productId` is a `Long`, which is why `OrderService`
   does `Long.valueOf(item.getProductId())` mid-checkout. Making the cart's id a
   `Long` end to end is the real fix; it is a schema change and was not done.
   → **Done in §27.2**, including the `varchar` → `bigint` migration. The
   `String(productId)` call above is gone, and so are both `Long.valueOf`
   conversions.
2. **There is no `/me`.** The profile is `GET /api/users/{sub}` — which works only
   because §24.3 made the Mongo `_id` *be* the Keycloak subject. The front end
   reading `tokenData.sub` and using it as a database key is that decision paying
   out. → **Superseded by §27.3**: `/api/users/me` exists, and the id-based route
   is ADMIN-only, because taking the id from the caller let any logged-in user read
   any profile.
3. **Signup does not log you in, and cannot.** `POST /api/users` provisions
   through the Admin API with the `ecom-admin` **service account** — a token that
   authorizes administration, not one representing the new human. Returning a user
   token would require enabling the password grant and handling the raw password
   server-side, which is the exact thing PKCE exists to avoid. So the signup page
   ends on a *Log in* button. This is the design working, not a gap.

### 26.6 Measured

| check | result |
|---|---|
| `GET /api/orders`, no orders | `200 []` |
| `GET /api/orders`, with an order | `200`, items included, one SQL statement |
| same call as a different user | `200 []` — no leakage |
| `POST /api/carts` with `productId:"abc"` | `400 INVALID_PRODUCT_ID` (was `500`) |
| `GET /api/orders` through the gateway, no token | `401` + `WWW-Authenticate: Bearer` |
| CORS preflight from `http://localhost:5173` | `200` with the three allow headers |
| CORS preflight from `http://evil.example` | `403` |
| `npm run build` | 109 modules, 314 KB (101 KB gzip) |
| Kafka path still intact | `Received order created event for order: 1` |

### 26.7 Not verified, and what is left

- **The browser login itself has not been run.** It is blocked on the Keycloak
  console change in §26.1, which is manual. Everything on either side of it is
  verified; the redirect round trip is not.
- **Signup and checkout through the UI** are likewise unexercised — both were
  tested against the API directly, with `X-User-ID` supplied by hand inside the
  Docker network, which is exactly the shortcut the browser cannot take.
- **The compensating-delete path in `addUser` is still unexercised** (§24.13),
  and a UI signup form makes duplicate-email attempts far more likely, so this is
  now more likely to be hit than it was.
- ~~**Roles are still not enforced.**~~ **Fixed in §27.4.** The gateway was
  `.anyExchange().authenticated()`, so `GET /api/users` — list *every* user — was
  reachable by any logged-in account, and §27.3 shows one user reading another's
  home address. No admin screen was built at this point, deliberately: building UI
  on an authorization guarantee that does not exist would bake in an assumption
  that was, at the time, false. It was built in §27.7, after the guarantee was
  real.
- **The front end is not containerised.** If that changes, the container must
  still be served on origin `http://localhost:5173`, or the gateway CORS bean and
  Keycloak's Web origins both have to change with it.

## 27. Closing the authorization gap — roles, `/me`, and one type for `productId`

The front end in §26 was built against a backend that authenticated everyone and
authorized no one. This section closes that, plus three bugs the browser exposed
that Postman never could.

It is deliberately written plainer than §15–§25. Each part is: **what was wrong,
what it looks like now, why that shape and not another.**

---

### 27.1 A duplicate signup answered `502`

**Wrong.** Signing up with an email that already existed returned
`502 Bad Gateway`. Nothing was down. The log said:

```
KeycloakAdminException: Keycloak POST .../admin/realms/ecom-app/users
  -> 409 CONFLICT {"errorMessage":"User exists with same email"}
```

Keycloak answered correctly. Two pieces of our code turned it into a lie:

1. `RestClientConfig` caught **every** error status with one exception type and
   formatted the status into a string — after which it was gone.
2. `GlobalExceptionHandler` mapped that one type unconditionally to `BAD_GATEWAY`.

The comment there said *"the failure is in Keycloak, not in this service."* True,
but it conflates two different things: **Keycloak is broken** and **Keycloak
correctly rejected your input**. Only the first is a 5xx.

This is the same bug as the `NumberFormatException` → 500 in §26.2, and it has the
same cost: a 5xx tells a client *the server broke, retry*, when the truth is *that
email is taken, retrying can never work*.

**Now.** The exception carries the status and a parsed reason:

```java
throw new KeycloakAdminException(
        response.getStatusCode(),
        extractReason(objectMapper, body, response.getStatusCode()),
        "Keycloak " + request.getMethod() + " " + request.getURI() + " -> " + …,
        null);
```

and the handler decides from it:

| Keycloak said | client gets | why |
|---|---|---|
| `409`, `400` — a 4xx about the input | that same status | the caller's problem, and fixable by them |
| `401`, `403` | `502` | **our** service-account credentials are wrong |
| `5xx`, or no response at all | `502` | genuinely upstream |

**Why 401/403 are not passed through.** They look like client errors but are not:
they mean the `ecom-admin` secret is wrong or the service account lost
`manage-users`. Worse, passing a `401` to the browser trips the front end's
response interceptor, which calls `logOut()` — a server misconfiguration would log
every user out.

**Why the body changed too.** The old response embedded `e.getMessage()`, which
contains `http://keycloak:8080/admin/realms/ecom-app/users`. Signup is the one
anonymous route, so that handed the in-network topology to anybody who asked. The
full string now stays in the log; the response gets the parsed `errorMessage`, or
a flat sentence for a 502.

**One gap this exposed.** `defaultStatusHandler` only runs when there **is** a
response. A Keycloak that is simply *down* throws `ResourceAccessException`, which
had no handler at all — so it fell to Spring's 500, and the gateway's circuit
breaker then masked that as a 503. The 502 branch was unreachable for the one case
it was named after. Verified by stopping the container, then fixed with the
missing handler:

```
docker stop ecom_keycloak
-> java.net.UnknownHostException: keycloak  →  502  (was: 500 → masked as 503)
```

---

### 27.2 `productId` was three types across six places

**Wrong.** The same id had three representations:

| where | Java | on the wire |
|---|---|---|
| `ProductResponse.id` | `Long` | **number** |
| `CartItemRequest` / `CartItemResponse` | `String` | string |
| `CartItem.productId` → `cart_item.product_id` | `String` → `varchar` | — |
| `OrderItem.productId` → `order_item.product_id` | `Long` → `bigint` | — |
| `OrderItemDto.productId` | `String` | **string** |

The last row is the one nobody notices, because **MapStruct converts `Long` to
`String` silently** — no warning, no cast in the source. So `GET /api/orders`
returned `"productId":"7"` while `GET /api/products` returned `"id":7`, and in
JavaScript `order.items[0].productId === product.id` was **always false**.

In Java the mismatch was caught at every boundary, which is why it survived: the
compiler forced a `Long.valueOf(...)` at each crossing and each one looked local
and reasonable. The cost only became visible with a client that has no type
system.

**Now.** `Long` from the DTO through the entity to the column. The proof that it
worked is what got *deleted* — both conversions:

```java
Long productId = Long.valueOf(request.getProductId());        // CartService, gone
orderItem.setProductId(Long.valueOf(item.getProductId()));    // OrderService, gone
```

**The schema step is the one that cannot be skipped.** `ddl-auto: update` **never
changes a column type** — it only adds. Postgres will not implicitly cast
`varchar` to `bigint`, so leaving it produces a *runtime* failure on the first
query rather than an error at startup:

```sql
DELETE FROM cart_item;   -- carts are ephemeral; makes the next line unfailable
ALTER TABLE cart_item ALTER COLUMN product_id TYPE bigint USING product_id::bigint;
```

The `USING` clause is mandatory. Without the `DELETE` it fails loudly on any
non-numeric row, which is a feature — it means the data was already broken.

**Why `Long` and not `String`.** Standardising on `String` is defensible in
general — opaque ids survive a later switch to UUIDs. It is wrong *here*, because
`product.id` is a JPA `@GeneratedValue Long` and `order_item.product_id` is
already `bigint`. `String` would mean converting in more places than before, and
would give up the type check that catches a `productId`/`orderId` transposition.

**The error handling got better as a side effect.** With a `Long`, a junk id fails
at Jackson (`HttpMessageNotReadableException`) or at path binding
(`MethodArgumentTypeMismatchException`) — **before any service code runs**. The
`NumberFormatException` handler became dead code and was replaced by one covering
those two. Validation moved from a service method someone has to remember to a
framework boundary nobody can bypass.

**One cross-service catch.** `notification/payload/OrderItemDto` had its own
`String productId` and consumes the Kafka event. Jackson coerces a JSON number
into a `String` field without complaint, so notification would have kept working —
which is precisely how the inconsistency survived this long. It changed too.

---

### 27.3 `/api/users/{id}` let anyone read anyone

**Wrong.** `UserController.getUserById` takes the id from the path and never
compares it to `X-User-ID`, and the gateway was `.anyExchange().authenticated()`.
Measured, not theorised — one user asking for another:

```
GET /api/users/c3e7c457-…   as  def64c9c-…   ->  200
{"email":"user1@example.com","phone":"555-0101",
 "addressDto":{"street":"1 Main St","city":"Springfield",…}}
```

Name, email, phone, home address. `PUT` and `DELETE /{id}` had the same shape, so
any logged-in customer could also **edit or delete any account**.

**Why this one endpoint and not the others.** Every cart and order endpoint takes
**no id at all** — the only input is a header the gateway overwrites from the
token. Authorization there is *structural*: there is nothing to forget, because
there is no parameter to check. `/users/{id}` was the one place that broke the
pattern, and it broke silently.

**Now.** A `/me` trio restores the pattern:

```java
@GetMapping("/me")
public ResponseEntity<UserResponse> getCurrentUser(@RequestHeader("X-User-ID") String userId)
```

No ordering problem with `/{id}` — a literal path segment outranks a template in
Spring's matching regardless of declaration order.

`/me` alone does not close the hole, since `/{id}` still exists; §27.4 does. But it
also buys a second thing: the front end stops encoding the `_id == sub` decision
from §24.3. If that ever changes, one endpoint moves instead of every client.

---

### 27.4 Roles, finally enforced

The gateway had extracted roles since §24 and enforced none of them. The comment
said to tighten *"once the log line shows the role actually arriving"* — which it
had, for a while.

**Now**, with the order being load-bearing:

```java
.pathMatchers(HttpMethod.POST, "/api/users").permitAll()
.pathMatchers("/api/users/me").authenticated()
.pathMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
.pathMatchers(HttpMethod.POST,   "/api/products/**").hasRole("ADMIN")
.pathMatchers(HttpMethod.PUT,    "/api/products/**").hasRole("ADMIN")
.pathMatchers(HttpMethod.PATCH,  "/api/products/**").hasRole("ADMIN")
.pathMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
.anyExchange().authenticated()
```

**First match wins**, so `/api/users/me` must come before `/api/users/**`.
Reversed, every customer is locked out of their own profile — and it would look
like the `/me` endpoint was broken rather than the rule order.

**The catch-all stayed `authenticated()`, not `hasRole("CUSTOMER")`.** An admin
holds `ADMIN` and not necessarily `CUSTOMER`, so the stricter rule would 403
admins out of shopping. Roles here are capabilities, not a hierarchy — nothing in
Keycloak makes `ADMIN` imply `CUSTOMER`.

Measured with a real token:

| as a CUSTOMER | before | after |
|---|---|---|
| `GET /api/users/me` | — | `200`, own profile |
| `GET /api/users` | `200` — every profile | `403` |
| `GET /api/users/{someone else}` | `200` + address | `403` |
| `DELETE /api/products/1` | `200` | `403` |
| catalogue, carts, orders | `200` | `200` |
| `POST /api/users` (no token) | `201` | `201` |

The converter's `logger.info` dropped to `debug` at the same time — it ran on
every single request, and a `403` is now the evidence it existed for.

**The first `ADMIN` has to be console-made.** `addUser` names the role from the
enum and ignores anything the request says, so self-service promotion is
impossible — which is the point. That is the same circularity that forces signup
to be `permitAll()`, and it cannot be designed away.

---

### 27.5 Deleting a user left half of them behind

**Wrong.** `removeUser` deleted the Mongo profile and nothing else. The Keycloak
account survived: it could still log in, still got a valid token, still passed the
gateway — and then 404'd on `/me` and every cart call. This is the exact mirror of
the orphan `addUser` already compensates for, and it never got the matching call.

Harmless while nothing called it. An admin Delete button would have manufactured
orphans routinely, which is why it was fixed *before* the UI, not after.

**Now**: Mongo first, then Keycloak. The ordering is the whole decision.

Keycloak cannot join the transaction either way, so one order must be picked and
the question is *which failure is recoverable*:

| order | if the second step fails | recoverable? |
|---|---|---|
| Keycloak, then Mongo | a profile nobody can log in as | **no** — unreachable *and* undeletable here, because `findById` still succeeds but names a dead account |
| **Mongo, then Keycloak** | an account with no profile | **yes** — the state `addUser` already produces, clearable in the console |

The Keycloak failure is logged loudly and **not** rethrown. The profile really is
gone, so reporting failure would be a lie that invites a retry which then 404s.

Verified end to end:

```
before   keycloak: 1   mongo: 1
DELETE -> 200
after    keycloak: 0   mongo: 0
```

---

### 27.6 The stored `role` had to go

Assigning `ADMIN` in the console and then looking at the profile showed
`"role":"CUSTOMER"`. Both were "right": Keycloak had the new role, Mongo had the
value written once at signup. Nothing syncs backward.

The comment in `addUser` — *"the enum is the single source of the role name, so
Mongo and Keycloak cannot drift apart"* — was true only for the signup path. The
moment anyone uses the console, which is the **only** way to make an admin, they
drift by construction.

**A field that can only ever go stale is worse than no field.** It was removed
from `User`, from `UserResponse`, and `$unset` from the nine existing documents.
`UserRole` stays as the enum naming the realm roles — `CUSTOMER` for what signup
assigns, `ADMIN` to document the vocabulary the gateway's rules expect.

Authorization never read it — the gateway reads the token and has never touched
Mongo — so this changed nothing about who can do what. It removed a second,
wrong answer to "what role is this user?"

> The general shape: when two systems own overlapping state and only one is the
> system of record, **do not cache the other's answer** unless something keeps it
> honest. Roles live in Keycloak. Read the token.

---

### 27.7 The admin UI, and what a front-end role check is worth

Built only after §27.4, and the order was the point: a screen that hides a button
while the endpoint stays open is decoration, and worse than nothing because it
invites you to trust it.

| file | what |
|---|---|
| `hooks/useRoles.js` | `useAppRoles` / `useHasRole` / `useIsAdmin`, reads `realm_access.roles` |
| `components/AdminRoute.jsx` | wraps `ProtectedRoute` — anonymous gets "log in", customer gets "admins only" |
| `pages/AdminUsers.jsx` | list, delete |
| `pages/AdminProducts.jsx` | create, edit, delete |

Roles are read fresh every render and never cached. The claim is already frozen at
token-issue time — a cached copy would only add a second way to be stale.

> **This is UX, not security.** These hooks decide what a user *sees*. What a user
> may *do* is decided by the gateway, against a signed token, on every request.
> Anyone can edit `tokenData` in devtools and reveal every admin screen, and every
> call those screens make still returns `403`.

Three deliberate absences:

- **The Users table shows no roles.** It cannot — `GET /api/users` returns
  profiles, and after §27.6 profiles have no role. The right fix is not to put the
  field back.
- **You cannot delete yourself.** That leaves a live token whose `/me` is gone, and
  if you are the only admin, nobody can reach the screen again.
- **Deleting a product does not clear it from carts.** Cart rows hold only a
  `productId`; the lookup that fails happens at checkout. Fixing it properly means
  a cascade or a checkout that tolerates a missing product — noted, not done.

---

### 27.8 Two smaller things

**Addresses moved to `.env`.** `client.js` had `baseURL: 'http://localhost:8080'`
and `authConfig.js` hardcoded Keycloak. Both now read `src/config.js`, backed by
`VITE_*` vars. Vite only exposes `VITE_`-prefixed vars to the bundle at all —
which is what stops a stray key reaching a browser by accident. The file is
gitignored (the root `.gitignore`'s `.env` matches at any depth), so every
variable is written out in README.md and every one also has a fallback in
`config.js` — a fresh clone runs with no `.env` at all.

Two details worth keeping straight. Substitution happens at **build time**, so
editing `.env` needs a dev-server restart, not a reload. And `redirectUri` is
deliberately *not* configurable: it is `window.location.origin`, because it must
equal the origin the browser is actually on or Keycloak rejects the redirect —
reading it from the browser makes disagreement impossible.

This was the real coupling, not the directory. The front end stays in-repo: the
`productId` change touched backend DTOs, an entity, a schema **and**
`frontend/src/api/cart.js`, and in one repo that is a single atomic commit. Split
repos buy independent deploy cadence, which nothing here needs, and cost the
ability to move a contract and both its sides together.

**Zipkin became a Grafana datasource.** Not a new container — there is one Zipkin,
in the root compose file, and the observability stack simply put `grafana` on
`ecom-network` so the name resolves, exactly as `prometheus` already did. Adding a
second would have given a second, empty Zipkin and a `container_name` collision.

The payoff over Zipkin's own UI at `:9411` is `tracesToLogsV2`: a span links to
the Loki logs carrying the same trace id, which Spring Boot already writes into
every line as `[<traceId>-<spanId>]`. That needs an explicit `uid: loki` on the
Loki datasource — otherwise Grafana generates a random uid per install and the
link breaks silently.

---

### 27.9 Measured, and what is still not

| check | result |
|---|---|
| duplicate email signup | `409` `{"detail":"User exists with same email"}` (was `502`) |
| duplicate username signup | `409` with Keycloak's message |
| Keycloak stopped, then signup | `502`, internal URL in the log only (was `500` → masked `503`) |
| `productId` on products / cart / orders | `1` / `1` / `1` — number on every hop |
| `{"productId":"abc"}` and `/carts/items/abc` | `400 INVALID_REQUEST`, before service code |
| customer → `GET /api/users` | `403` |
| customer → `GET /api/users/{other}` | `403` (was `200` with a home address) |
| customer → `DELETE /api/products/1` | `403` |
| customer → `/me`, catalogue, carts, orders | `200` |
| `GET /api/users/me` | returns exactly the token's `sub` |
| `DELETE /api/users/{id}` | Keycloak `1→0`, Mongo `1→0` |
| Kafka path after the `Long` change | `Received order created event for order: 4` |
| Grafana → Zipkin | `["cloud-gateway","eureka-server","notification-service",…]` |
| `npm run build` | 114 modules, 323 KB (102 KB gzip) |

**Not verified.** The admin screens have never been driven by an `ADMIN` token —
`hasRole("ADMIN")` was proved only from the deny side, because minting an admin
requires the Keycloak console and `addUser` refuses to do it. The positive path is
one console assignment and a fresh login away.

Also still open: the gateway's own circuit-breaker fallback turns a downstream 5xx
into `503 "User service is unavailable"`, which **masks** the more precise status
underneath — that is how the `ResourceAccessException` gap in §27.1 stayed hidden.
And a 503 immediately after a rebuild is usually Eureka registration lag, not a
fault; it clears in about six seconds.

## 28. One broker — Spring Cloud Bus onto Kafka, and what a refresh really does

§21–§23 moved the *domain event* from RabbitMQ to Kafka and left Rabbit running
for one job: Spring Cloud Bus. This section finishes that — and then explains the
mechanism the Bus exists to trigger, because "refresh without restarting" hides
more than it says.

---

### 28.1 What was actually still using Rabbit

Less than the compose file suggested:

| module | Bus | Stream/Kafka | AMQP on classpath |
|---|---|---|---|
| configserver | `bus-amqp` | — | yes |
| product | `bus-amqp` | — | yes |
| user | `bus-amqp` | — | yes |
| order | *commented out* | binder-kafka | **no** |
| notification | — | binder-kafka | **no** |

Two things fell out of that table before a single line changed.

**Dead configuration.** `docker-compose.yml` passed `SPRING_RABBITMQ_*` and set
`depends_on: rabbitmq` for **all five** services, but order and notification had
no AMQP dependency at all — §23 removed it. Four env vars and a health-gated wait,
doing nothing, on two services. Same story for
`spring.rabbitmq.connection-timeout: 2s` in `order-service.yml`: it had been
inert for two sections. Unknown configuration keys are not an error, which is
exactly how both survived.

**A live defect.** `OrderConfigDemoController` is `@RefreshScope`, and
`order-service.yml` claimed its values "can be changed and pushed with the
`/actuator/busrefresh` bus without rebuilding the image." They could not —
order-service was not on the bus. The comment described an intention, not the
system. Order joining the bus in this section is what made it true.

---

### 28.2 The change

Four poms, `spring-cloud-starter-bus-amqp` → `spring-cloud-starter-bus-kafka`
(order uncommented as the Kafka variant). Bus rides Spring Cloud Stream, so
**only the binder changes** — nothing about `/actuator/busrefresh` is affected.

The broker address needs no bus-specific property; Bus reads the ordinary binder
setting:

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          brokers: ${KAFKA_BROKERS:localhost:29092}
```

Declared once in the shared `config/application.yml`, and **again** in
config-server's own `application.yaml` — the config server cannot fetch
configuration from itself, the same reason its logging block is duplicated.

Then the compose cleanup: the `rabbitmq` service, the `rabbitmq_data` volume, 20
`SPRING_RABBITMQ_*` lines and 5 `depends_on` entries. Verified afterwards by
looking inside the built images rather than at the poms — zero AMQP classes in
any of the four jars.

**One structural consequence.** Kafka moves from the side of the startup graph to
its root:

```
before   postgres ─┬─ keycloak
                   └─ rabbitmq → config-server → eureka → { services }
         kafka ──────────────────────────────► { order, notification }

after    postgres ─── keycloak
         kafka ─────► config-server → eureka → { services }
```

Nothing starts before config-server, and config-server now waits on Kafka. A
broken broker blocks everything rather than two services. That is the honest cost
of one broker instead of two, and it is worth stating rather than discovering.

---

### 28.3 What "refresh without restarting" actually means

This is the part worth understanding, because the phrase suggests magic and the
reality has sharp edges.

`POST /actuator/busrefresh` on **any one service**:

1. `RefreshBusEndpoint` publishes a `RefreshRemoteApplicationEvent` locally.
2. `BusAutoConfiguration` bridges it to the `springCloudBusOutput` binding →
   Kafka topic **`springCloudBus`** (`BusConstants.DESTINATION`).
3. Every service subscribes through `springCloudBusInput`, each with its **own
   anonymous consumer group**. That detail is the fanout: distinct groups mean
   every service receives a copy, rather than competing for one message the way
   notification-service's named `notification` group does on `order.exchange`.
   Same broker, same binder, opposite semantics — chosen by whether a group name
   is set.
4. `RefreshListener` calls `ContextRefresher.refresh()` on each service.

And on each service, `ContextRefresher` does four things:

1. Rebuilds the `Environment`, which **re-fetches configuration from
   config-server over HTTP**. Visible from the other side, in config-server's log:
   `Adding property source: Config resource 'file [/app/config/order-service.yml]'`.
2. Diffs old against new property sources.
3. Publishes `EnvironmentChangeEvent` with the changed keys, and re-binds
   `@ConfigurationProperties` beans.
4. Calls `RefreshScope.refreshAll()`, which **destroys the cached instance** of
   every `@RefreshScope` bean.

**The ApplicationContext is never closed.** The servlet container keeps its
connections, HikariCP keeps its pool, the `EntityManagerFactory` and the Kafka
bindings are untouched. That is the entire reason this is not a restart.

**`@RefreshScope` is a scoped proxy.** Callers hold the proxy permanently; the
proxy delegates to a cached target instance. Refresh clears that cache, and the
next call builds a new target against the new `Environment` — which is the moment
a `@Value` is re-resolved. Beans are rebuilt **lazily**, not eagerly: nothing
happens until something asks.

That proxy is also the limit. **A `@Value` in an ordinary singleton is injected
once, at construction, and never read again.** No error, no warning — the refresh
reports success and that field silently keeps the old value. The mental model
that matters:

| refreshes | does not |
|---|---|
| `@Value` inside a `@RefreshScope` bean | `@Value` in a plain singleton |
| `@ConfigurationProperties` beans (re-bound) | `server.port`, datasource URL |
| Resilience4j and rate-limit values read per call | Logback rolling policy — the appender is built at startup (§ logging) |
| | `spring.cloud.stream` bindings |

Measured end to end: the demo value was changed on disk, `busrefresh` was posted
to **product-service only**, and **order-service** — a different container —
returned the new value. Only a bus-delivered refresh can do that; `@RefreshScope`
beans do not poll.

---

### 28.4 Side effects

Five, in rough order of how likely they are to bite.

**1. It broke the Eureka client on every service.** The first `busrefresh`
returned `500` and left config-server `unhealthy`:

```
BeanCreationException: Error creating bean 'scopedTarget.eurekaClient'
  ... EurekaClientAutoConfiguration$RefreshableEurekaClientConfiguration
  NullPointerException: Cannot invoke "Object.hashCode()" because "<parameter1>" is null
```

The Eureka client is **itself refresh-scoped** by default — `ConditionalOnRefreshScope`
→ `eureka.client.refresh.enable`, `matchIfMissing = true`. So every refresh
destroys and rebuilds it, and on this version that rebuild throws. All four bus
members logged it six times each; config-server merely showed it loudest, because
it is the one with a healthcheck that includes the discovery indicator.

**This has nothing to do with Kafka.** It is `/refresh` plus Eureka, and it would
have behaved identically on Rabbit. It only became visible now because order
joined the bus and `busrefresh` was finally exercised end to end — a bug that
existed for three sections and was never triggered.

Fixed with `eureka.client.refresh.enable: false` in both config files. The trade
is that `eureka.*` changes need a restart rather than a refresh; nobody re-points
discovery at runtime, and the alternative is a broken discovery client after
every refresh.

The general lesson is broader than Eureka: **refresh is a destroy-and-recreate
lifecycle event, not a value assignment.** Any `@RefreshScope` bean with
`@PreDestroy`, an open resource, or in-memory state will have that destroyed. If
a bean is expensive or stateful, refresh-scoping it is a decision, not a default.

**2. Anonymous consumer groups accumulate.** One per service *per restart*, never
cleaned up. After a few rebuild cycles:

```
anonymous.1e60f4e3-…   anonymous.28cf99b8-…   anonymous.34ee4c5c-…
anonymous.3b1db33a-…   anonymous.40798310-…   anonymous.7ab315eb-…
anonymous.9133b63c-…   anonymous.b1635b41-…   notification
```

Eight dead groups and one real one. On Rabbit these were auto-delete queues that
vanished when the connection dropped; Kafka has no equivalent, so they linger
until `offsets.retention.minutes` expires them (7 days by default). Harmless
here, noise in `kafka-consumer-groups --list`, and worth knowing before it looks
like a leak.

**3. It is a broadcast with no acknowledgement.** `busrefresh` returns `204`
immediately — that means *published*, not *applied*. Nothing reports which
services accepted it. Some can succeed while others fail, and a partial refresh
looks exactly like a complete one from the caller's side.
`spring.cloud.bus.ack.enabled` and `/actuator/busenv` tracing exist for this and
are not enabled here.

**4. A service that is down misses the event entirely.** The anonymous group is
created fresh on each start, so there is no committed offset to resume from and
nothing is replayed. That is *fine* — a restarting service fetches current config
at startup anyway. The genuinely bad case is a service that is **up but
partitioned from Kafka**: it misses the refresh, reports nothing, and quietly
keeps serving stale configuration.

**5. Every service re-fetches configuration simultaneously.** Step 1 of
`ContextRefresher` is an HTTP call to config-server, and a broadcast makes them
all do it at once. With four services this is invisible; it is a thundering herd
in proportion to fleet size, against a single config-server.

---

### 28.5 Measured

| check | result |
|---|---|
| `springCloudBus` topic created | yes, 1 partition, alongside `order.exchange` |
| busrefresh posted to **product-service only** | `204`, and **order-service** picked up its new value |
| config-server health after refresh (post-fix) | `healthy`, `status: UP` |
| `scopedTarget.eurekaClient` errors after fix | **0** across all four bus members |
| AMQP classes inside the four built images | **0** |
| compose services after removal | 13, no `rabbitmq` |
| domain event still flowing | cart `201` → checkout `201` → `Received order created event for order: 5` |

**Not verified.** Bus behaviour with more than one instance of the same service —
every instance has its own anonymous group, so all of them should refresh, but
that has not been run. And `busshutdown` still exists (`ShutdownBusEndpoint`,
`ShutdownListener` calling `SpringApplication.exit`) and has deliberately not been
tried: it stops services, and the endpoint is exposed because
`management.endpoints.web.exposure.include` is `"*"`.

That last point deserves a note, because it is a real exposure. Asking a running
service what it exposes:

```
GET /actuator  ->  "busenv"  "busrefresh"  "busshutdown"  "refresh"
```

Boot's own `/actuator/shutdown` is disabled by default, and the shared
`application.yml` already explains at length why it stays off. Bus's endpoints are
**different endpoints with a different gate** — Spring Cloud
`@ConditionalOnProperty` flags (`spring.cloud.bus.*.enabled`), not Boot's actuator
access rules — so the reasoning that keeps `shutdown` off never applied to them.
Two are worth naming:

- **`busshutdown`** stops every service on the bus, in one POST.
- **`busenv`** pushes environment properties to every service on the bus — that is
  arbitrary configuration injection into every JVM at once, which is a strictly
  larger capability than turning things off.

Nothing stands in front of either except that the actuator port is unpublished
except on loopback (§16.2). That was sufficient when the actuator held read-only
information; it is doing more work now. Setting
`spring.cloud.bus.env.enabled: false` costs nothing here, since nothing uses it.
