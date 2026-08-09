# ecom_microservices

A Spring Boot 4 / Spring Cloud microservices e-commerce backend, fully
containerised with Docker Compose. Includes service discovery, centralised
configuration, an API gateway, distributed tracing, metrics, and log
aggregation.

---

## Table of contents

- [Stack](#stack)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Environment variables](#environment-variables)
- [Running](#running)
- [Service URLs](#service-urls)
- [API endpoints](#api-endpoints)
- [Testing the endpoints](#testing-the-endpoints)
- [Observability](#observability)
- [Docker command reference](#docker-command-reference)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)

---

## Stack

| | |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.0 |
| Spring Cloud | 2025.1.2 |
| Databases | PostgreSQL 16 (product, order), MongoDB 8 (user) |
| Messaging | RabbitMQ — Spring Cloud Bus **and** order → notification events |
| Cache / rate-limit store | Redis 8 (gateway token buckets) |
| Resilience | Resilience4j circuit breakers, Redis-backed rate limiting |
| Discovery | Netflix Eureka |
| Config | Spring Cloud Config Server (native profile) |
| Gateway | Spring Cloud Gateway (reactive / WebFlux) |
| Tracing | Micrometer Tracing + Zipkin |
| Metrics | Micrometer + Prometheus |
| Logs | Grafana Alloy → Loki → Grafana |

---

## Architecture

```
                        ┌───────────────┐
      browser / API ───►│ cloud-gateway │  :8080   ← the only way in
      client            └───────┬───────┘
                                │ rate limiter + circuit breaker per route
                                │ lb:// (resolved via Eureka)
             ┌──────────────────┼──────────────────┐
             ▼                  ▼                  ▼
      product-service      user-service      order-service
          :8081               :8082              :8083     (internal only)
             │                  │                  │
             ▼                  ▼                  ▼
        PostgreSQL           MongoDB          PostgreSQL
                                                   │
                        order-service also calls ──┤ (sync, RestClient + lb://)
                        product-service + user-service
                                                   │
                                                   │ OrderCreatedEvent (async)
                                                   ▼
                                    ┌──────────────────────────────┐
                                    │ order.exchange  (topic)      │
                                    │        │ order.tracking      │
                                    │        ▼                     │
                                    │ order.queue     (durable)    │
                                    └──────────────┬───────────────┘
                                                   ▼
                                        notification-service  :8084
                                                              (internal only)

  Supporting: config-server :8888 · eureka-server :8761 · rabbitmq
              redis-server (gateway rate-limit buckets) · zipkin :9411
```

**Only the gateway is reachable from your machine for `/api/**`.** The business
services listen on 8081/8082/8083/8084 inside the Docker network only — they are
deliberately not published to the host.

Two different kinds of inter-service call are in that picture, and the difference
matters:

- **Synchronous** — order-service → product-service / user-service over
  `RestClient` with `lb://` and a circuit breaker. The caller waits, and knows
  exactly who it is calling.
- **Asynchronous** — order-service → RabbitMQ → notification-service. The caller
  publishes to an exchange and returns. It has no reference to
  notification-service at all; stopping that service does not affect order
  placement, the events simply queue up.

---

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (includes
  Docker Compose v2)
- ~4 GB free RAM for the full stack
- No local JDK or Maven needed — every service builds inside its own
  multi-stage Dockerfile

---

## Environment variables

Create a **`.env`** file in the project root:

```dotenv
# ─── PostgreSQL (shared by product-service and order-service) ───
POSTGRES_USER=user
POSTGRES_PASSWORD=password

# ─── MongoDB (user-service) ───
MONGO_USERNAME=user
MONGO_PASSWORD=password
MONGO_PORT=27018

# ─── RabbitMQ ───
RABBITMQ_USER=admin
RABBITMQ_PASS=admin1234
RABBITMQ_PORT=5672
RABBITMQ_MGMT_PORT=15672

# ─── Databases ───
PRODUCT_DB=product_db
USER_DB=user_db
ORDER_DB=order_db

# ─── Config server ───
CONFIG_PORT=8888
SPRING_CLOUD_CONFIG_URI=http://config-server:8888

# ─── Eureka ───
EUREKA_SERVER_PORT=8761

# ─── API gateway ───
GATEWAY_PORT=8080

# ─── notification-service (RabbitMQ consumer) ───
NOTIFICATION_PORT=8084

# ─── pgAdmin (optional — the service is commented out in docker-compose.yml) ───
PGADMIN_DEFAULT_EMAIL=admin@admin.com
PGADMIN_DEFAULT_PASSWORD=admin
```

**Notes**

- `MONGO_PORT`, `RABBITMQ_PORT`, `RABBITMQ_MGMT_PORT`, `CONFIG_PORT`,
  `EUREKA_SERVER_PORT` and `GATEWAY_PORT` are **host-side** ports only. Change
  them freely to avoid local conflicts — traffic between containers always uses
  the fixed internal ports (27017, 5672, 8888, 8761, 8080).
- `SPRING_CLOUD_CONFIG_URI` uses the Docker **service name**, not `localhost`.
  Inside a container, `localhost` means that container itself.
- There is intentionally **no `PRODUCT_PORT` / `USER_PORT` / `ORDER_PORT`** —
  those services are no longer published to the host. Reach them through the
  gateway.
- `NOTIFICATION_PORT` is bound to `127.0.0.1` in `docker-compose.yml`, so it is
  reachable from this machine but not from the LAN. notification-service has no
  HTTP API — it is a pure RabbitMQ consumer — so the mapping is only there for
  future use.
- `redis-server` needs no variable. It is fixed at `127.0.0.1:6379`, runs with
  persistence disabled, and exists only for the gateway's rate-limit token
  buckets, which are per-second state that *should* be lost on restart.

---

## Running

### 1. Main stack

```bash
docker compose up -d --build --wait
```

- `--build` rebuilds each image from its Dockerfile
- `--wait` blocks until every service with a healthcheck is healthy, and exits
  non-zero if any fails

Startup order is enforced by health-gated `depends_on`:

```
rabbitmq → config-server → eureka-server → { product, user, order, notification }
                                          → cloud-gateway  (also waits on redis-server)
```

A cold start takes roughly 60–90 seconds.

### 2. Observability stack (optional)

```bash
docker compose -f evaluate-prometheus/docker-compose.yaml up -d
```

This stack joins the main stack's network as an **external network**, which is
how Prometheus scrapes the services directly. Start the main stack first — the
network has to exist.

### Stopping

```bash
docker compose down          # stop and remove containers, keep data
docker compose down -v       # …and delete volumes (DESTROYS all data)
```

---

## Service URLs

| Service | URL | Notes |
|---|---|---|
| **API gateway** | http://localhost:8080 | all `/api/**` traffic |
| Eureka dashboard | http://localhost:8761 | also proxied at http://localhost:8080/eureka |
| Config server | http://localhost:8888 | e.g. `/product-service/default` |
| Zipkin | http://localhost:9411 | distributed traces |
| RabbitMQ management | http://localhost:15672 | login = `RABBITMQ_USER` / `RABBITMQ_PASS` |
| Grafana | http://localhost:3000 | observability stack only |
| Prometheus | http://localhost:9090 | observability stack only |
| PostgreSQL | `localhost:5433` | |
| MongoDB | `localhost:27018` | |
| Redis | `localhost:6379` | loopback only; gateway rate-limit buckets |
| notification-service | `localhost:8084` | loopback only; **no HTTP API** — RabbitMQ consumer |

**Actuator endpoints are not exposed to the host.** They run on port 9090
inside each container, which is deliberately not published — that keeps
`/actuator/shutdown` off your network. To reach them:

```bash
docker exec ecom_gateway curl -s http://product-service:9090/actuator/health
```

---

## API endpoints

All paths are relative to the gateway, **http://localhost:8080**.

### Product service

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/products` | All active products |
| GET | `/api/products/{id}` | Get product by ID |
| GET | `/api/products/search?keyword=` | Search by name (in-stock, active only) |
| POST | `/api/products` | Create a product |
| PUT | `/api/products/{id}` | Update product by ID |
| DELETE | `/api/products/{id}` | **Soft delete** — sets `active=false`, row remains |

### User service

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/users` | Get all users |
| GET | `/api/users/{id}` | Get user by ID (Mongo ObjectId string) |
| POST | `/api/users` | Create a user — `role` defaults to `CUSTOMER` |
| PUT | `/api/users/{id}` | Update user by ID |
| DELETE | `/api/users/{id}` | **Hard delete** — document is removed |

### Order service

| Method | Endpoint | Description | Headers |
|---|---|---|---|
| GET | `/api/carts` | Get current user's cart | `X-User-ID` (required) |
| POST | `/api/carts` | Add item to cart | `X-User-ID` (required) |
| DELETE | `/api/carts/items/{productId}` | Remove item from cart | `X-User-ID` (required) |
| POST | `/api/orders` | Create order from cart, clears it | `X-User-ID` (required) |

> **Note:** `X-User-ID` is a plain request header (string), **not** auth — every
> cart/order request needs it since there is no auth layer yet.

### Config-refresh demo endpoints — not routed

Two `@RefreshScope` demo endpoints exist for showing Spring Cloud Config
live-refresh:

| Endpoint | Service |
|---|---|
| `GET /api/product/demo/message` | product-service |
| `GET /api/order/demo/message` | order-service |

They are **not reachable through the gateway**, and since the business services
are no longer published to the host, they are not reachable from your machine
at all. The controllers map to *singular* prefixes (`/api/product/demo`,
`/api/order/demo`) while the gateway routes match only the *plural* ones
(`/api/products/**`, `/api/orders/**`).

Reach them from inside the network:

```bash
docker exec ecom_gateway curl -s http://product-service:8081/api/product/demo/message
```

To route them properly, either add the singular paths to
`gateway/src/main/java/com/ramesh/gateway/config/GatewayConfig.java`, or
renumber the controllers onto the plural prefixes.

---

## Testing the endpoints

### Smoke test

```bash
curl -i http://localhost:8080/api/products
```

A `200` proves the whole chain works: gateway → Eureka lookup →
product-service → PostgreSQL.

### Create a product

```bash
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Whole Milk",
    "description": "Kroger brand",
    "price": 5.00,
    "stockQuantity": 10,
    "category": "Dairy",
    "imageUrl": null
  }'
```

### Create a user

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Steve",
    "lastName": "Mara",
    "email": "steve@example.com",
    "phone": "2828981433",
    "addressDto": {
      "street": "145 Main St",
      "city": "Louisville",
      "state": "KY",
      "zipcode": "40400",
      "country": "USA"
    }
  }'
```

Copy the returned `id` — that is your `X-User-ID` below.

### Add to cart

```bash
curl -X POST http://localhost:8080/api/carts \
  -H "Content-Type: application/json" \
  -H "X-User-ID: <user-id-from-above>" \
  -d '{ "productId": "1", "quantity": 2 }'
```

This single request crosses three services — order-service calls
product-service to check stock and user-service to validate the user. It is the
best endpoint for confirming distributed tracing end to end.

### View cart, then place the order

```bash
curl http://localhost:8080/api/carts -H "X-User-ID: <user-id>"

curl -X POST http://localhost:8080/api/orders -H "X-User-ID: <user-id>"
```

### Async messaging — watch the order event arrive

Placing an order publishes an `OrderCreatedEvent` to the `order.exchange` topic
exchange with routing key `order.tracking`. notification-service is bound to it
through `order.queue` and logs what it receives.

```bash
# 1. tail the consumer
docker logs -f ecom_notification &

# 2. place an order (see above)
curl -X POST http://localhost:8080/api/orders -H "X-User-ID: <user-id>"
```

Within a few hundred milliseconds:

```
c.r.n.consumer.OrderEventConsumer : Received order event: OrderCreatedEvent(orderId=1,
  userId=6a78f3c8..., status=CONFIRMED, items=[OrderItemDto(id=1, productId=1,
  quantity=3, price=5.00, subTotal=15.00)], totalAmount=15.00, createdAt=...)
```

Inspect the broker side without needing the management UI login — `rabbitmqctl`
authenticates with the Erlang cookie:

```bash
docker exec rabbitmq rabbitmqctl list_queues name durable messages consumers
docker exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key
```

`order.queue` should show `consumers 1` and `messages 0`. **`consumers 0` means
notification-service is down** — messages then accumulate in the durable queue
and are delivered when it returns, which is the point of the pattern. Try it:

```bash
docker stop ecom_notification
curl -X POST http://localhost:8080/api/orders -H "X-User-ID: <user-id>"   # still 201
docker exec rabbitmq rabbitmqctl list_queues name messages                # order.queue 1
docker start ecom_notification                                            # then delivered
```

The reverse also holds — stopping the **broker** does not break order placement:

```bash
docker stop rabbitmq
curl -X POST http://localhost:8080/api/orders -H "X-User-ID: <user-id>"   # still 201
docker logs ecom_order | grep "LOST OrderCreatedEvent"
```

The order commits and the response is unaffected, because the publish runs on
`AFTER_COMMIT` and on a separate thread (`OrderEventPublisher`). The event itself
**is** lost — that is at-most-once delivery, and the `ERROR` log above is the only
record of it. A transactional outbox is what would make it survivable; see
`details.md` §21.4.

### Rate limiting

`/api/products/**` and `/api/users/**` are rate limited at the gateway by a
Redis-backed token bucket (10 requests/second, burst 20, per client IP). Exceed
it and the gateway answers **429** without touching the downstream service:

```bash
for i in $(seq 1 40); do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/products &
done; wait; echo
```

If you see no `429`s at all, the limiter is probably failing *open* rather than
being off — check `X-RateLimit-Remaining` on a response, where `-1` means Redis
was unreachable, and see `details.md` §20.2.

### Search

```bash
curl "http://localhost:8080/api/products/search?keyword=milk"
```

> **PowerShell users:** `curl` is an alias for `Invoke-WebRequest`, which takes
> different arguments. Use `curl.exe` explicitly, or run these from Git Bash or
> WSL.

---

## Observability

### Tracing — Zipkin

1. Generate cross-service traffic with `POST /api/carts` (above).
2. Open http://localhost:9411 and search by service `cloud-gateway`.
3. A single trace should contain spans from **multiple** services —
   `cloud-gateway` → `order-service` → `product-service`. One trace spanning
   several services is the proof that trace context propagates across service
   boundaries; several single-service traces for one request means it does not.

Sampling is `1.0` (trace everything), set in
`configserver/src/main/resources/config/application.yml`.

### Metrics — Prometheus + Grafana

With the observability stack running:

- Prometheus targets — http://localhost:9090/targets (all jobs should be `UP`)
- Grafana — http://localhost:3000 (anonymous admin access enabled)

Services expose `/actuator/prometheus` on internal port 9090.

### Logs — Loki

Every service writes to `logs/<service-name>/<service-name>.log`, which is
mounted into Alloy and shipped to Loki. Query in Grafana by the `service_name`
label:

```logql
{service_name="cloud-gateway"}
```

---

## Docker command reference

```bash
# ── Lifecycle ─────────────────────────────────────────────
docker compose up -d                      # start (no rebuild)
docker compose up -d --build              # rebuild images, then start
docker compose up -d --build --wait       # …and block until healthy
docker compose down                       # stop, keep volumes
docker compose down -v                    # stop and DELETE all data
docker compose restart <service>          # restart one service

# ── One service at a time (use the compose service name) ──
docker compose up -d --build cloud-gateway
docker compose build --no-cache product-service   # bypass a stale build cache

# ── Inspect ───────────────────────────────────────────────
docker compose ps                         # this project's services
docker ps                                 # all containers, with port bindings
docker compose logs -f order-service      # follow logs
docker logs ecom_gateway --tail 100       # by container name
docker inspect -f '{{.State.Health.Status}}' ecom_eureka_server

# ── Shell / debugging ─────────────────────────────────────
docker exec -it ecom_postgres psql -U user -d product_db
docker exec -it ecom_mongodb mongosh -u user -p password
docker exec ecom_gateway curl -s http://localhost:9090/actuator/health

# ── RabbitMQ (no credentials needed — uses the Erlang cookie) ──
docker exec rabbitmq rabbitmqctl list_queues name durable messages consumers
docker exec rabbitmq rabbitmqctl list_exchanges name type
docker exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key

# ── Redis (gateway rate-limit buckets) ────────────────────
docker exec ecom_redis redis-cli keys "request_rate_limiter*"
docker exec ecom_redis redis-cli flushall     # reset the buckets between tests
```

**Compose service names differ from container names in this project:**

| compose service | container name |
|---|---|
| `cloud-gateway` | `ecom_gateway` |
| `product-service` | `ecom_product` |
| `user-service` | `ecom_user` |
| `order-service` | `ecom_order` |
| `notification-service` | `ecom_notification` |
| `config-server` | `ecom_config_server` |
| `eureka-server` | `ecom_eureka_server` |
| `redis-server` | `ecom_redis` |
| `rabbitmq` | `rabbitmq` |

Use the **compose service name** with `docker compose …`, and the **container
name** with plain `docker …`.

---

## Troubleshooting

**A service stays `unhealthy` forever**
The healthcheck uses `curl`, which is not in the base JRE image — only the
`configserver`, `eureka`, and `gateway` Dockerfiles install it. A missing
`curl` exits 127, which Docker cannot tell apart from a failed application. Add
this before adding a healthcheck:
```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
```

**A container sits in `Created` and never starts**
It is blocked on a `depends_on: condition: service_healthy` that can never be
met. Find the unhealthy dependency with `docker compose ps`.

**`404` from the gateway on a valid path**
The gateway may have started without its config. Check that
`spring.application.name` in `gateway/src/main/resources/application.yml`
matches a filename in `configserver/src/main/resources/config/`. Because the
config import is marked `optional:`, a mismatch fails **silently**.

**A `pom.xml` change has no effect**
`docker compose up -d` recreates containers from the *existing* image. Use
`--build` — and if a stale layer is still served,
`docker compose build --no-cache <service>`.

**`Connection refused` to Eureka in the startup logs**
Transient and self-healing; the Eureka client retries until the server is up.

**An order succeeds but notification-service logs nothing**
Check `docker exec rabbitmq rabbitmqctl list_queues name messages consumers`. A
`messages` count above zero with `consumers 0` means the consumer is down and the
events are waiting — correct behaviour. `messages 0` with nothing logged means
the message was never routed: the routing key in `config/order-service.yml` and
the binding key in `config/notification-service.yml` have to match, and an
unroutable publish is **discarded silently** by the broker. Also check
`docker logs ecom_order | grep "LOST OrderCreatedEvent"` — the publish is
fire-and-forget after commit, so a broker problem shows up there, not in the
order response.

**`PRECONDITION_FAILED - inequivalent arg` at startup**
The queue or exchange already exists with different arguments. Both
`order/config/RabbitMQConfig.java` and `notification/config/RabbitMQConfig.java`
declare the same topology, so changing one without the other causes this. Delete
the queue in the management UI, or make both declarations agree.

**Every request to `/api/products` returns 429**
The gateway rate limiter, working as intended. Reset it with
`docker exec ecom_redis redis-cli flushall`, or raise the limits in
`gateway/src/main/java/com/ramesh/gateway/config/GatewayConfig.java`.

**Cannot reach `localhost:8081`**
Working as intended — the business services are not published to the host. Use
the gateway on 8080. For a path the gateway does not route (see
[config-refresh demo endpoints](#config-refresh-demo-endpoints--not-routed)),
call it from inside the network with `docker exec`.

---

## Project layout

```
ecom_microservices/
├── configserver/          # Spring Cloud Config Server
│   └── src/main/resources/config/     # ← config served to all services
│       ├── application.yml            #   shared by every service
│       ├── cloud-gateway.yml
│       ├── product-service.yml
│       ├── user-service.yml
│       ├── order-service.yml
│       ├── notification-service.yml
│       └── eureka-server.yml
├── eureka/                # Eureka discovery server
├── gateway/               # Spring Cloud Gateway (compose service: cloud-gateway)
├── product/               # product-service — PostgreSQL
├── user/                  # user-service    — MongoDB
├── order/                 # order-service   — PostgreSQL, calls the other two,
│                          #                   publishes OrderCreatedEvent
├── notification/          # notification-service — RabbitMQ consumer, no HTTP API
├── evaluate-prometheus/   # Grafana + Prometheus + Loki + Alloy stack
├── logs/                  # per-service log output (mounted into Alloy)
├── docker-compose.yml     # main stack
├── init-db.sql            # creates product_db and order_db on first boot
├── details.md             # full technical documentation
└── README.md
```

The four business services follow the same internal structure: `controllers/`,
`services/`, `repositories/`, `entities/`, `dtos/`, `mappers/` (MapStruct) and
`exceptions/`. **notification-service is the exception** — it has no HTTP layer
and no database, so it is just `config/` (the RabbitMQ topology), `consumer/`
(the `@RabbitListener`) and `payload/` (its own copy of the event classes).

That copy is deliberate. The event DTOs are duplicated in
`order/dtos/` and `notification/payload/` rather than shared through a common
jar, so the two services can version their view of the event independently — see
`details.md` §21.3 for the mechanism that makes cross-package deserialization
work, and the one thing that will break it.
