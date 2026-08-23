# ecom_microservices

A Spring Boot 4 / Spring Cloud microservices e-commerce backend, fully
containerised with Docker Compose. Includes service discovery, centralised
configuration, an API gateway, OAuth2/OIDC authentication via Keycloak,
event-driven messaging over Kafka, distributed tracing, metrics, and log
aggregation.

---

## Table of contents

- [Stack](#stack)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Environment variables](#environment-variables)
- [Keycloak setup (one-time)](#keycloak-setup-one-time)
- [Running](#running)
- [Service URLs](#service-urls)
- [Authentication](#authentication)
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
| Databases | PostgreSQL 16 (product, order, keycloak), MongoDB 8 (user) |
| Identity | Keycloak 26.6.2 — OIDC provider, realm `ecom-app` |
| Messaging | **Kafka** (KRaft, no ZooKeeper) — order → notification, via Spring Cloud Stream |
| | **RabbitMQ** — Spring Cloud Bus (`/actuator/busrefresh`) only |
| Cache / rate-limit store | Redis 8 (gateway token buckets) |
| Resilience | Resilience4j circuit breakers, Redis-backed rate limiting |
| Discovery | Netflix Eureka |
| Config | Spring Cloud Config Server (native profile) |
| Gateway | Spring Cloud Gateway (reactive / WebFlux) + OAuth2 resource server |
| Tracing | Micrometer Tracing + Zipkin |
| Metrics | Micrometer + Prometheus |
| Logs | Grafana Alloy → Loki → Grafana |

Both brokers are running, on purpose. **Kafka** carries the domain event
(`OrderCreatedEvent`, order-service → notification-service). **RabbitMQ** is left
in place for Spring Cloud Bus config refresh — see `details.md` §23 for the
RabbitMQ → Kafka migration and what did *not* port.

---

## Architecture

```
                             ┌──────────┐
    browser / Postman ──────►│ keycloak │ :8443   ← log in here, get a JWT
            │                └──────────┘
            │ Authorization: Bearer <access_token>
            ▼
    ┌───────────────┐
    │ cloud-gateway │  :8080   ← the only way in
    └───────┬───────┘
            │ 1. validate JWT (issuer + signature)
            │ 2. strip inbound X-User-ID, rewrite it from the token's "sub"
            │ 3. rate limiter + circuit breaker per route
            │ 4. lb:// (resolved via Eureka)
   ┌────────┼─────────────────┐
   ▼        ▼                 ▼
product-service  user-service  order-service
   :8081          :8082          :8083        (internal only)
   │                │  │            │
   ▼                ▼  │            ▼
PostgreSQL       MongoDB│       PostgreSQL
                        │            │
        Keycloak Admin ─┘            │ order-service also calls (sync,
        API (provisioning)           │ RestClient + lb://) product-service
                                     │ and user-service
                                     │
                                     │ OrderCreatedEvent (async)
                                     ▼
                          ┌────────────────────────────┐
                          │ kafka topic  order.exchange│
                          │ consumer group "notification"│
                          └─────────────┬──────────────┘
                                        ▼
                             notification-service :8084
                                                  (internal only)

  Supporting: config-server :8888 · eureka-server :8761 · rabbitmq (Cloud Bus)
              redis-server (gateway rate-limit buckets) · zipkin :9411
```

**Only the gateway is reachable from your machine for `/api/**`.** The business
services listen on 8081/8082/8083/8084 inside the Docker network only — they are
deliberately not published to the host.

Three kinds of call are in that picture, and the differences matter:

- **Synchronous** — order-service → product-service / user-service over
  `RestClient` with `lb://` and a circuit breaker. The caller waits, and knows
  exactly who it is calling.
- **Asynchronous** — order-service → Kafka → notification-service. The caller
  publishes to a topic and returns. It has no reference to notification-service
  at all; stopping that service does not affect order placement, and the consumer
  group's committed offset means it resumes where it left off.
- **Administrative** — user-service → Keycloak Admin API, to create the account
  and assign its realm role at signup. This one is *not* a domain call: it runs
  before the Mongo write, with a compensating delete if the write fails.

---

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (includes
  Docker Compose v2)
- ~6 GB free RAM for the full stack (Kafka and Keycloak both want their share)
- No local JDK or Maven needed — every service builds inside its own
  multi-stage Dockerfile

---

## Environment variables

Create a **`.env`** file in the project root:

```dotenv
# ─── PostgreSQL (product-service, order-service, keycloak) ───
POSTGRES_USER=user
POSTGRES_PASSWORD=password

# ─── MongoDB (user-service) ───
MONGO_USERNAME=user
MONGO_PASSWORD=password
MONGO_PORT=27018

# ─── RabbitMQ (Spring Cloud Bus) ───
RABBITMQ_USER=admin
RABBITMQ_PASS=admin1234
RABBITMQ_PORT=5672
RABBITMQ_MGMT_PORT=15672

# ─── Databases ───
PRODUCT_DB=product_db
USER_DB=user_db
ORDER_DB=order_db

# ─── Keycloak admin client (used by user-service to provision users) ───
KEYCLOAK_ADMIN_CLIENT_ID=ecom-admin
KEYCLOAK_ADMIN_CLIENT_SECRET=<paste from Keycloak → Clients → ecom-admin → Credentials>

# ─── Config server ───
CONFIG_PORT=8888
SPRING_CLOUD_CONFIG_URI=http://config-server:8888

# ─── Eureka ───
EUREKA_SERVER_PORT=8761

# ─── API gateway ───
GATEWAY_PORT=8080

# ─── notification-service (Kafka consumer) ───
NOTIFICATION_PORT=8084

# ─── pgAdmin (optional — the service is commented out in docker-compose.yml) ───
PGADMIN_DEFAULT_EMAIL=admin@admin.com
PGADMIN_DEFAULT_PASSWORD=admin
```

**Notes**

- `KEYCLOAK_ADMIN_CLIENT_SECRET` is the only real secret here and it cannot be
  guessed or defaulted — Keycloak generates it. Get it from the console after
  [the setup below](#keycloak-setup-one-time). Without it user-service starts
  fine and then fails on the first `POST /api/users`, because the admin token is
  fetched lazily.
- **Kafka needs no variables.** Its listeners, KRaft settings and cluster id are
  fixed in `docker-compose.yml`; only the *client* side is variable, and that is
  set per service as `KAFKA_BROKERS`.
- `MONGO_PORT`, `RABBITMQ_PORT`, `RABBITMQ_MGMT_PORT`, `CONFIG_PORT`,
  `EUREKA_SERVER_PORT` and `GATEWAY_PORT` are **host-side** ports only. Change
  them freely to avoid local conflicts — traffic between containers always uses
  the fixed internal ports (27017, 5672, 8888, 8761, 8080).
- `SPRING_CLOUD_CONFIG_URI` uses the Docker **service name**, not `localhost`.
  Inside a container, `localhost` means that container itself. The same trap is
  why `KEYCLOAK_SERVER_URL`, `KEYCLOAK_JWK_SET_URI` and `KAFKA_BROKERS` are set
  to service names in `docker-compose.yml` while the YAML defaults point at
  `localhost` for IDE runs.
- There is intentionally **no `PRODUCT_PORT` / `USER_PORT` / `ORDER_PORT`** —
  those services are no longer published to the host. Reach them through the
  gateway.
- `redis-server` needs no variable. It is fixed at `127.0.0.1:6379`, runs with
  persistence disabled, and exists only for the gateway's rate-limit token
  buckets, which are per-second state that *should* be lost on restart.

---

## Keycloak setup (one-time)

Keycloak is not preconfigured by an import file — the realm is created by hand and
lives in Postgres (`keycloak_db`), so it survives `docker compose down` but **not**
`down -v`. Start the stack first, then:

Console → http://localhost:8443 — log in as `admin` / `admin`
(`KC_BOOTSTRAP_ADMIN_*` in `docker-compose.yml`; this is the **master** realm
admin, which is a different thing from the client below).

**1. Create the realm** `ecom-app`.

**2. Client `oauth2-pkce`** — the login client, public.

| setting | value |
|---|---|
| Client ID | `oauth2-pkce` |
| Client authentication | **Off** (public) |
| Standard flow | **On** |
| PKCE method | **S256** (Advanced tab) |
| Valid redirect URIs | `https://oauth.pstmn.io/v1/callback`, and later `http://localhost:5173/*` |
| Web origins | `http://localhost:5173` (needed only for a browser front end) |

**3. Client `ecom-admin`** — the provisioning client, confidential.

| setting | value |
|---|---|
| Client ID | `ecom-admin` |
| Client authentication | **On** (confidential) |
| Service accounts roles | **On** |
| Standard flow / Direct access grants | **Off** — it is not a login client |

Then: **Credentials** tab → copy the client secret into `.env`, and **Service
accounts roles** → *Assign role* → **Filter by clients** → `realm-management` →
assign **`manage-users`** and **`view-realm`**. The "filter by clients" toggle is
the step people miss — those are client roles of `realm-management`, so the
default "filter by realm roles" view does not list them.

**4. Realm roles** — create `CUSTOMER` and `ADMIN`.

**Without the `ROLE_` prefix.** The gateway's converter adds it, so
`hasRole("CUSTOMER")` matches the authority `ROLE_CUSTOMER`. Naming the realm role
`ROLE_CUSTOMER` produces `ROLE_ROLE_CUSTOMER`, which matches nothing and surfaces
as a 403 with no error anywhere. See `details.md` §24.4.

Promotion to `ADMIN` is a console action — Users → *user* → Role mapping. Signup
always yields `CUSTOMER` and cannot request anything else; `UserRequest` has no
`role` field. `details.md` §24.11 covers the other four ways roles can be
assigned, including groups and default roles.

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
postgres ─┬─ keycloak
          └─ rabbitmq → config-server → eureka-server → { product, user, order, notification }
kafka ────────────────────────────────────────────────► { order, notification }
                                                      → cloud-gateway (also waits on redis-server)
```

user-service depends on Keycloak with plain `service_started`, not
`service_healthy` — the admin token is fetched on the first signup, never at
startup, so it does not need Keycloak up to boot. (Keycloak has no `healthcheck:`
to wait on anyway, though `KC_HEALTH_ENABLED` is true and `/health/ready` is
served on management port 9000.)

A cold start takes roughly 90–120 seconds.

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
docker compose down -v       # …and delete volumes (DESTROYS all data,
                             #  including the Keycloak realm)
```

---

## Service URLs

| Service | URL | Notes |
|---|---|---|
| **API gateway** | http://localhost:8080 | all `/api/**` traffic |
| **Keycloak** | http://localhost:8443 | admin console; `admin` / `admin` |
| Eureka dashboard | http://localhost:8761 | also proxied at http://localhost:8080/eureka |
| Config server | http://localhost:8888 | e.g. `/product-service/default` |
| Zipkin | http://localhost:9411 | distributed traces |
| RabbitMQ management | http://localhost:15672 | login = `RABBITMQ_USER` / `RABBITMQ_PASS` |
| Kafka | `localhost:29092` | host-side listener; `kafka:9092` in-network |
| Grafana | http://localhost:3000 | observability stack only |
| Prometheus | http://localhost:9090 | observability stack only |
| PostgreSQL | `localhost:5433` | |
| MongoDB | `localhost:27018` | |
| Redis | `localhost:6379` | loopback only; gateway rate-limit buckets |
| notification-service | `localhost:8084` | loopback only; **no HTTP API** — Kafka consumer |

Keycloak is published as **8443 → 8080**: plain HTTP on a port that usually means
HTTPS. That is a dev-only arrangement, and it is deliberate — `KC_HOSTNAME` is
pinned to `http://localhost:8443` so the issuer in every token is the same string
whether the token was fetched from the host or from inside the network.

**Actuator endpoints are not exposed to the host.** They run on port 9090
inside each container, which is deliberately not published — that keeps
`/actuator/shutdown` off your network. To reach them:

```bash
docker exec ecom_gateway curl -s http://product-service:9090/actuator/health
```

---

## Authentication

Every `/api/**` route needs a bearer token, with exactly three exceptions:

| open | why |
|---|---|
| `POST /api/users` | signup — it is what *creates* the account, so requiring a token would be circular |
| `/actuator/**` | the compose healthcheck cannot fetch a token on every probe |
| `/eureka`, `/eureka/**` | a browser cannot attach a bearer token to an address-bar navigation |

Note the signup rule is matched on **method + path**: `GET /api/users` (list every
user) still requires a token.

### Getting a token

Postman is the documented path, since the login client uses authorization code +
PKCE and that is awkward from curl:

- Auth type **OAuth 2.0**, grant type **Authorization Code (With PKCE)**
- Auth URL `http://localhost:8443/realms/ecom-app/protocol/openid-connect/auth`
- Token URL `http://localhost:8443/realms/ecom-app/protocol/openid-connect/token`
- Client ID `oauth2-pkce`, no secret
- Client credentials: **Send client credentials in body**
- Callback `https://oauth.pstmn.io/v1/callback`

Use the **`access_token`**, not the `id_token`. The gateway validates access
tokens; an id_token is for the client's own use and will be rejected.

### What the gateway does with it

1. Validates the signature against Keycloak's JWKS and the `iss` claim against
   `http://localhost:8443/realms/ecom-app`.
2. Reads `realm_access.roles` and turns each into a `ROLE_*` authority.
3. **Strips any inbound `X-User-ID` header and rewrites it from the token's
   `sub`.**

Step 3 is the important one for anyone who used the pre-auth version of this API:

> **`X-User-ID` is no longer a header you send.** The gateway removes whatever you
> send and replaces it with the token's subject. Forging it is not possible
> through the gateway, and omitting it is correct.

That subject is also the user's Mongo `_id` — signup sets it from the Keycloak
user id rather than letting Mongo generate one, so `GET /api/users/{sub}` resolves
directly. `details.md` §24.3 explains why the alternative (a separate
`keycloakId` field) fails, and §24.12 why the two ids are not kept side by side.

---

## API endpoints

All paths are relative to the gateway, **http://localhost:8080**. All require
`Authorization: Bearer <access_token>` unless marked **public**.

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
| POST | `/api/users` | **public** — signup. Creates the Keycloak account **and** the Mongo profile, assigns realm role `CUSTOMER` |
| GET | `/api/users` | Get all users |
| GET | `/api/users/{id}` | Get user by ID — the **Keycloak user id** (the JWT `sub`), not a Mongo ObjectId |
| PUT | `/api/users/{id}` | Update user by ID |
| DELETE | `/api/users/{id}` | **Hard delete** — the Mongo document is removed (the Keycloak account is *not*) |

`POST /api/users` accepts `username`, `password`, `firstName`, `lastName`,
`email`, `phone`, `addressDto`. There is no `id` and no `role`: the id comes back
from Keycloak, and the role is always `CUSTOMER`. `password` is write-only — it is
accepted on the way in and never echoed back.

### Order service

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/carts` | Get the current user's cart |
| POST | `/api/carts` | Add an item to the cart |
| DELETE | `/api/carts/items/{productId}` | Remove an item from the cart |
| POST | `/api/orders` | Create an order from the cart, clears it |

No `X-User-ID` column any more — see [Authentication](#authentication). The
gateway injects it from the token.

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

### 1. Smoke test — the gateway is up and enforcing

```bash
curl -i http://localhost:8080/api/products
```

Expect **401** with a `WWW-Authenticate: Bearer` header. That is a *successful*
smoke test now: the gateway is running and the security chain is active. A `200`
here would mean the resource-server config never loaded.

### 2. Sign up — the one call that needs no token

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "steve",
    "password": "pass123",
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

**201**, and the returned `id` is a **UUID** — not a 24-character ObjectId. That
is the check that the id came from Keycloak. Confirm both sides:

```bash
# Keycloak console → Users → steve → Role mapping tab shows CUSTOMER
docker exec ecom_mongodb mongosh -u user -p password --quiet \
  --eval 'db.getSiblingDB("user_db").user_table.find({}, {_id:1, email:1})'
```

The `_id` and the Keycloak user id must be the same string, and there must be no
`keycloakId` field.

A duplicate email comes back as **409** with Keycloak's own message
(`User exists with same email`) rather than a bare "Conflict" — the error handler
reads the response body, which is the difference between a five-minute and a
two-hour diagnosis.

### 3. Log in and use the token

Get an access token as described in [Authentication](#authentication), then:

```bash
TOKEN=<paste access_token>

curl http://localhost:8080/api/users -H "Authorization: Bearer $TOKEN"     # 200
curl http://localhost:8080/api/products -H "Authorization: Bearer $TOKEN"  # 200
```

Confirm the role actually arrived:

```bash
docker logs ecom_gateway | grep "Extracted roles"
# Extracted roles for sub def64c9c-...: [offline_access, CUSTOMER,
#                                        default-roles-ecom-app, uma_authorization]
```

### 4. Add to cart — no `X-User-ID`

```bash
curl -X POST http://localhost:8080/api/carts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{ "productId": "1", "quantity": 2 }'
```

**201**, with no user header sent at all. This is the end-to-end proof: the
gateway derived the id from the token, order-service looked it up in user-service,
and it resolved.

Now try to forge it:

```bash
curl -X POST http://localhost:8080/api/carts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-User-ID: someone-else" \
  -d '{ "productId": "1", "quantity": 2 }'

curl http://localhost:8080/api/carts -H "Authorization: Bearer $TOKEN"
```

The item lands in **your** cart. The forged header was stripped before routing.

This single request crosses three services — order-service calls product-service
to check stock and user-service to validate the user. It is the best endpoint for
confirming distributed tracing end to end.

### 5. View cart, then place the order

```bash
curl http://localhost:8080/api/carts -H "Authorization: Bearer $TOKEN"

curl -X POST http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"
```

### 6. Async messaging — watch the order event arrive

Placing an order publishes an `OrderCreatedEvent` through Spring Cloud Stream's
`StreamBridge` to the Kafka topic **`order.exchange`** (a leftover name from the
AMQP version — see `details.md` §23). notification-service consumes it in the
consumer group **`notification`**.

```bash
# 1. tail the consumer
docker logs -f ecom_notification &

# 2. place an order (see above)
curl -X POST http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"
```

Within a few hundred milliseconds:

```
c.r.n.consumer.OrderEventConsumer : Received order event: OrderCreatedEvent(orderId=1,
  userId=def64c9c-..., status=CONFIRMED, items=[OrderItemDto(id=1, productId=1,
  quantity=3, price=5.00, subTotal=15.00)], totalAmount=15.00, createdAt=...)
```

Inspect the broker side:

```bash
docker exec ecom_kafka kafka-topics --bootstrap-server localhost:29092 --list
docker exec ecom_kafka kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group notification
```

The `--describe` output is the one to read. `CURRENT-OFFSET`, `LOG-END-OFFSET` and
`LAG` per partition tell you exactly what the consumer has and has not processed —
**`LAG 0` means caught up**, and a non-zero lag with no consumer attached means
messages are waiting. That is the durability guarantee, and it is a different
mechanism from RabbitMQ's: nothing is removed on delivery, the group's *offset*
moves. Stop the consumer and the lag grows:

```bash
docker stop ecom_notification
curl -X POST http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"   # still 201
docker exec ecom_kafka kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group notification                                                 # LAG 1
docker start ecom_notification                                                    # then delivered
```

It resumes rather than skipping because the binding declares
`group: notification`. Without that line Stream invents an anonymous group with
`startOffset: latest`, and everything published while the service was down is
silently skipped.

Read the raw topic without a consumer group:

```bash
docker exec ecom_kafka kafka-console-consumer --bootstrap-server localhost:29092 \
  --topic order.exchange --from-beginning --max-messages 5
```

The reverse also holds — stopping the **broker** does not break order placement:

```bash
docker stop ecom_kafka
curl -X POST http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"   # still 201
docker logs ecom_order | grep "LOST OrderCreatedEvent"
```

The order commits and the response is unaffected, because the publish runs on
`AFTER_COMMIT` and on a separate thread (`OrderEventPublisher`). The event itself
**is** lost — that is at-most-once delivery, and the `ERROR` log above is the only
record of it. A transactional outbox is what would make it survivable; see
`details.md` §21.4.

### 7. Rate limiting

`/api/products/**` and `/api/users/**` are rate limited at the gateway by a
Redis-backed token bucket (10 requests/second, burst 20, per client IP). Exceed
it and the gateway answers **429** without touching the downstream service:

```bash
for i in $(seq 1 40); do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/products &
done; wait; echo
```

If you see no `429`s at all, the limiter is probably failing *open* rather than
being off — check `X-RateLimit-Remaining` on a response, where `-1` means Redis
was unreachable, and see `details.md` §20.2.

### 8. Search

```bash
curl "http://localhost:8080/api/products/search?keyword=milk" \
  -H "Authorization: Bearer $TOKEN"
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

# ── Kafka (order events) ──────────────────────────────────
docker exec ecom_kafka kafka-topics --bootstrap-server localhost:29092 --list
docker exec ecom_kafka kafka-topics --bootstrap-server localhost:29092 \
  --describe --topic order.exchange
docker exec ecom_kafka kafka-consumer-groups --bootstrap-server localhost:29092 --list
docker exec ecom_kafka kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group notification        # CURRENT-OFFSET / LOG-END-OFFSET / LAG
docker exec ecom_kafka kafka-console-consumer --bootstrap-server localhost:29092 \
  --topic order.exchange --from-beginning --max-messages 5

# ── Keycloak ──────────────────────────────────────────────
docker exec ecom_gateway curl -s http://keycloak:9000/health/ready  # the keycloak
                                                                     # image has no curl
docker logs ecom_keycloak --tail 50
docker exec -it ecom_postgres psql -U user -d keycloak_db -c '\dt'

# ── RabbitMQ (Spring Cloud Bus only) ──────────────────────
docker exec rabbitmq rabbitmqctl list_queues name durable messages consumers
docker exec rabbitmq rabbitmqctl list_exchanges name type

# ── Redis (gateway rate-limit buckets) ────────────────────
docker exec ecom_redis redis-cli keys "request_rate_limiter*"
docker exec ecom_redis redis-cli flushall     # reset the buckets between tests
```

The Kafka CLI is invoked against **`localhost:29092`** *inside* the container —
that is the `PLAINTEXT_HOST` listener, and it is what the healthcheck uses too.
`kafka:9092` also works from inside; both are the same broker.

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
| `kafka` | `ecom_kafka` |
| `keycloak` | `ecom_keycloak` |
| `rabbitmq` | `rabbitmq` |

Use the **compose service name** with `docker compose …`, and the **container
name** with plain `docker …`.

---

## Troubleshooting

**`401` on every `/api/**` call**
Expected without a token. With one, check in this order: is it the
**`access_token`** and not the `id_token`; has it expired (5 minutes by default);
and does its `iss` claim read exactly `http://localhost:8443/realms/ecom-app`? A
token fetched against a different hostname fails the issuer check even though the
signature is valid — that is what `KC_HOSTNAME` pins.

**`403` where you expected `200`**
An authorization outcome, not an error, so nothing is logged anywhere. It is
almost always the role name: check `docker logs ecom_gateway | grep "Extracted
roles"`. If the list shows `ROLE_CUSTOMER`, the realm role was created with the
prefix — the converter adds it, so the realm role must be plain `CUSTOMER`.

**Signup returns `502` with a Keycloak message**
The message is the diagnosis, forwarded verbatim. `User exists with same email` is
a duplicate. `invalid_client` or `unauthorized_client` means
`KEYCLOAK_ADMIN_CLIENT_ID` / `KEYCLOAK_ADMIN_CLIENT_SECRET` do not match the
`ecom-admin` client. `403` from the Admin API means the service account is missing
`manage-users` or `view-realm`. `404` on the role means `CUSTOMER` was never
created in the realm.

**Signup works in Docker but fails from the IDE**
`configserver/…/user-service.yml` defaults `client-id` to `ecom-admin-cli`, while
the client in the realm is `ecom-admin`. Compose supplies the right value via
`KEYCLOAK_ADMIN_CLIENT_ID`; an IDE run with no environment falls back to a client
that does not exist.

**A service stays `unhealthy` forever**
The healthcheck uses `curl`, which is not in the base JRE image — only the
`configserver`, `eureka`, and `gateway` Dockerfiles install it. A missing
`curl` exits 127, which Docker cannot tell apart from a failed application. Add
this before adding a healthcheck:
```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
```
The same shape of bug bit Kafka: the probe was `kafka_broker-api-versions` with an
underscore, which exits 126, so a perfectly healthy broker never went green and
everything gated on it waited forever.

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

**`No resolvable bootstrap urls given in bootstrap.servers`**
Kafka is not reachable under the name the client was given. Inside a container
`localhost` is that container — `KAFKA_BROKERS` must be `kafka:9092`. The same
misconfiguration can also surface as
`ProvisioningException … Caused by: TimeoutException`, which reads like a topic
problem and is not.

**An order succeeds but notification-service logs nothing**
Check the lag: `docker exec ecom_kafka kafka-consumer-groups --bootstrap-server
localhost:29092 --describe --group notification`. A non-zero `LAG` means the event
is on the topic and the consumer has not caught up — correct behaviour if the
service is down. `LOG-END-OFFSET` unchanged means the event was never published:
check `docker logs ecom_order | grep "LOST OrderCreatedEvent"`, since the publish
is fire-and-forget after commit and a broker problem shows up there rather than in
the order response. If the group does not exist at all, the binding name is wrong
— `spring.cloud.function.definition` must name the consumer bean, and the
Stream 3.x spelling `spring.cloud.stream.function.definition` binds nothing **in
silence**.

**Every request to `/api/products` returns 429**
The gateway rate limiter, working as intended. Reset it with
`docker exec ecom_redis redis-cli flushall`, or raise the limits in
`gateway/src/main/java/com/ramesh/gateway/config/GatewayConfig.java`.

**Cannot reach `localhost:8081`**
Working as intended — the business services are not published to the host. Use
the gateway on 8080. For a path the gateway does not route (see
[config-refresh demo endpoints](#config-refresh-demo-endpoints--not-routed)),
call it from inside the network with `docker exec`.

**The Keycloak realm is gone after a restart**
`docker compose down -v` deletes `postgres_data`, and the realm lives in
`keycloak_db` inside it. Plain `down` keeps it. There is no realm import file, so
the [setup](#keycloak-setup-one-time) has to be redone by hand — including a new
client secret in `.env`.

---

## Project layout

```
ecom_microservices/
├── configserver/          # Spring Cloud Config Server
│   └── src/main/resources/config/     # ← config served to all services
│       ├── application.yml            #   shared by every service
│       ├── cloud-gateway.yml          #   routes, resource-server, rate limits
│       ├── product-service.yml
│       ├── user-service.yml           #   keycloak.admin.* lives here
│       ├── order-service.yml
│       ├── notification-service.yml
│       └── eureka-server.yml
├── eureka/                # Eureka discovery server
├── gateway/               # Spring Cloud Gateway (compose service: cloud-gateway)
│   └── src/main/java/…/security/      # SecurityConfig + UserIdRelayFilter
├── product/               # product-service — PostgreSQL
├── user/                  # user-service    — MongoDB + Keycloak Admin API
├── order/                 # order-service   — PostgreSQL, calls the other two,
│                          #                   publishes OrderCreatedEvent to Kafka
├── notification/          # notification-service — Kafka consumer, no HTTP API
├── evaluate-prometheus/   # Grafana + Prometheus + Loki + Alloy stack
├── logs/                  # per-service log output (mounted into Alloy)
├── docker-compose.yml     # main stack
├── init-db.sql            # creates product_db, order_db and keycloak_db on first boot
├── details.md             # full technical documentation
└── README.md
```

The four business services follow the same internal structure: `controllers/`,
`services/`, `repositories/`, `entities/`, `dtos/`, `mappers/` (MapStruct) and
`exceptions/`. **notification-service is the exception** — it has no HTTP layer
and no database, so it is just `consumer/` (the `java.util.function.Consumer`
bean bound by Spring Cloud Stream) and `payload/` (its own copy of the event
classes).

That copy is deliberate. The event DTOs are duplicated in
`order/dtos/` and `notification/payload/` rather than shared through a common
jar, so the two services can version their view of the event independently — see
`details.md` §21.3 for the mechanism that makes cross-package deserialization
work, and the one thing that will break it.

`details.md` is the long-form companion to this file: §15–§20 cover the gateway,
resilience and rate limiting, §21–§23 the three messaging iterations (RabbitMQ →
Spring Cloud Stream → Kafka), and §24 the whole Keycloak story — including the
id-resolution bug in §24.3 that is the most transferable thing in it.
