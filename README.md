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
- [**Quick start**](#quick-start) — the order to do things in
- [Running on an older machine](#running-on-an-older-machine)
- [Environment variables](#environment-variables)
- [Running](#running)
- [Keycloak setup (one-time)](#keycloak-setup-one-time)
- [Service URLs](#service-urls)
- [Authentication](#authentication)
- [Front end](#front-end)
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
| Messaging | **Kafka** (KRaft, no ZooKeeper) — the only broker. Carries both the domain event (order → notification) and Spring Cloud Bus |
| Cache / rate-limit store | Redis 8 (gateway token buckets) |
| Resilience | Resilience4j circuit breakers, Redis-backed rate limiting |
| Discovery | Netflix Eureka |
| Config | Spring Cloud Config Server (native profile) |
| Gateway | Spring Cloud Gateway (reactive / WebFlux) + OAuth2 resource server |
| Tracing | Micrometer Tracing + Zipkin |
| Metrics | Micrometer + Prometheus |
| Logs | Grafana Alloy → Loki → Grafana |

**One broker.** Kafka carries two unrelated flows on two topics: the domain event
`OrderCreatedEvent` on `order.exchange` (order-service → notification-service,
consumer group `notification`), and Spring Cloud Bus on `springCloudBus`
(config refresh, every service, anonymous consumer groups). See `details.md` §23
for the RabbitMQ → Kafka migration of the domain event and what did *not* port,
and §28 for the Bus move that retired RabbitMQ entirely.

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

  Supporting: config-server :8888 · eureka-server :8761 · kafka (also Cloud Bus)
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

**On older hardware**, see [Running on an older
machine](#running-on-an-older-machine) before the first `up` — the MongoDB image
is the one pin that may need changing.

---

## Quick start

The whole sequence, in order. Steps 1 and 3–6 are one-time setup; after that,
starting the stack is just step 2.

**1. Create `.env` in the project root.** Copy the block from [Environment
variables](#environment-variables). Leave `KEYCLOAK_ADMIN_CLIENT_SECRET` as a
placeholder for now — Keycloak has not generated it yet, and nothing needs it
until step 4.

**2. Start the stack.**

```bash
docker compose up -d --build --wait
```

About 90–120 seconds cold. What `--wait` does, and the startup order it enforces,
is in [Running](#running).

**3. Configure Keycloak.** Console → http://localhost:8443, log in `admin` /
`admin`. Create the realm `ecom-app`, two clients, and two realm roles — the full
procedure with every field is in [Keycloak setup](#keycloak-setup-one-time).
**Nothing here is imported automatically**, and it has to happen *after* step 2
because the console does not exist until Keycloak is running.

**4. Paste the client secret into `.env`, then recreate user-service.**

```bash
# after setting KEYCLOAK_ADMIN_CLIENT_SECRET in .env
docker compose up -d user-service
```

**This is the step people miss.** Compose reads `.env` when a container is
*created*, so a user-service that is already running keeps the old placeholder no
matter how correct the file looks. The symptom is signup failing against Keycloak
with the stack apparently healthy — the admin token is fetched lazily on the
first `POST /api/users`, so nothing complains at startup.

**5. Sign up a user** — through the front end (step 7), or with [`POST
/api/users`](#2-sign-up--the-one-call-that-needs-no-token). It is the one call
that needs no token.

Do **not** create this user with the console's *Add user*. That makes a Keycloak
account with no Mongo profile: it logs in fine, and then `GET /api/users/me` and
every cart call return 404. Signup is what writes both halves.

**6. Promote it to `ADMIN`** if you want the admin screens — a console action,
[step 5 of Keycloak setup](#keycloak-setup-one-time). Log out **fully** and back
in afterwards, or the change is invisible: roles are baked into a token when it
is issued.

**7. Start the front end.** Separate terminal, not containerised:

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

It needs its own `frontend/.env`, which is a different file from the root one —
see [Front end](#front-end).

**8. Or drive the API directly with Postman** — get a token as shown in [Getting
a token](#getting-a-token), then work through [Testing the
endpoints](#testing-the-endpoints).

**Optional:** the [observability stack](#2-observability-stack-optional)
(Prometheus + Grafana + Zipkin). Start it *after* the main stack — it joins that
stack's network as an external network, so the network has to exist first.

---

## Running on an older machine

One pin in `docker-compose.yml` is worth knowing about before the first run:

```yaml
mongodb:
  image: mongodb/mongodb-community-server:8.0-ubi8
```

MongoDB 8 does not start on some older Linux machines. If `ecom_mongodb` exits
immediately or never goes healthy, drop to the official MongoDB 7 image:

```yaml
mongodb:
  image: mongo:7
```

Nothing else changes — `mongo:7` takes the same `MONGO_INITDB_ROOT_*` variables,
stores data in the same `/data/db`, and ships `mongosh`, so the healthcheck in
`docker-compose.yml` works unaltered. user-service talks to it over the wire
protocol and neither knows nor cares which image is behind `mongodb:27017`.

**One caveat, and it is not optional:** this is a *downgrade*. MongoDB refuses to
start against a data directory written by a newer release, so if the `mongo_data`
volume already holds 8.x data the container will exit with a
`featureCompatibilityVersion` error. On a fresh machine there is nothing to
worry about. On a machine that has already run 8.x, the volume has to go:

```bash
docker compose down
docker volume rm ecom_microservices_mongo_data
docker compose up -d --build --wait
```

That destroys the user profiles in Mongo but **not** the Keycloak accounts, which
live in `keycloak_db` in Postgres. The two halves are then out of step: those
accounts can still log in, and every `/api/users/me` and cart call returns 404,
exactly as in step 5 above. Either delete those users in the Keycloak console and
sign up again, or start clean with `docker compose down -v`.

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

# (No RABBITMQ_* any more — the broker is gone. Spring Cloud Bus runs on Kafka,
#  which needs no credentials here: the listeners are PLAINTEXT.)

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
- `MONGO_PORT`, `CONFIG_PORT`, `EUREKA_SERVER_PORT` and `GATEWAY_PORT` are
  **host-side** ports only. Change them freely to avoid local conflicts — traffic
  between containers always uses the fixed internal ports (27017, 8888, 8761,
  8080).
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
postgres ─── keycloak
kafka ─────► config-server → eureka-server → { product, user, order, notification }
                                           → cloud-gateway (also waits on redis-server)
```

**Kafka is now a root dependency.** It used to sit off to the side, needed only by
order and notification; since Spring Cloud Bus moved onto it, config-server waits
on it too — and nothing starts before config-server. A broken broker now blocks
the whole stack instead of two services. That is the price of running one broker
rather than two, and it is worth knowing before debugging a stack that will not
start.

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
| Valid redirect URIs | `https://oauth.pstmn.io/v1/callback` **and** `http://localhost:5173/*` |
| Web origins | `http://localhost:5173` — **no wildcard, no trailing slash** |
| Valid post logout redirect URIs | `http://localhost:5173` |
| Direct access grants | On only while you need `grant_type=password` for curl/Postman — turn it **off** once the browser flow works |

**Redirect URIs and Web origins are different fields answering different
questions**, and the second one is the one that bites:

| field | checked by | question |
|---|---|---|
| Valid redirect URIs | the **authorization** endpoint | may I send the user back here with a code? |
| **Web origins** | the **token** endpoint | may a browser at this origin read my response? |

Web origins exists only to put `Access-Control-Allow-Origin` on the token
response, so Postman never needed it — Postman is a desktop app and has no
origin. Miss it and every *visible* step works: the login page appears, the
password is accepted, the browser lands back on `:5173/?code=…`, and then the
silent background token exchange is blocked by the browser. Nothing appears in the
Keycloak log or the gateway log, because the request never reached either.

Note the format difference: the redirect URI needs the wildcard, Web origins must
**not** have one — that field is compared against the `Origin` header verbatim.

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

**5. Make an admin.** There is no API for this and there deliberately never will
be — self-service promotion is privilege escalation, so the first `ADMIN` has to
be console-made, the same circularity that forces signup to be `permitAll()`.

1. **Sign up through the app first** (`/signup`, or `POST /api/users`). Do **not**
   use the console's *Add user*: that creates a Keycloak account with no Mongo
   profile, so the account logs in fine but `GET /api/users/me` and every cart
   call return 404. Signup is what writes both halves.
2. Console → realm **`ecom-app`** (top-left dropdown — every screen is silently
   the wrong realm if you skip this) → Users → *that user* → **Role mapping** →
   *Assign role*.
3. **Switch the filter to "Filter by realm roles".** The dialog defaults to
   *filter by clients*, and `ADMIN` is a realm role, so it does not appear until
   you change it. This is where people conclude the role does not exist.
4. **Log out fully and log in again.** Roles are baked into the token when it is
   issued, so an existing token never gains one. Clearing local state is not
   enough — Keycloak's SSO cookie survives and hands back the same session.

Verify with `GET /api/users`: `200` for an admin, `403` for a customer.

> Assigning a role in the console changes Keycloak and nothing else. That is
> correct — Keycloak is the system of record for roles, and user-service stores no
> copy of them (it used to, and the copy went stale the moment anyone used this
> screen). The token's `realm_access.roles` is the only answer.

---

## Service URLs

| Service | URL | Notes |
|---|---|---|
| **Front end** | http://localhost:5173 | React + Vite dev server — **not** a compose service, see [Front end](#front-end) |
| **API gateway** | http://localhost:8080 | all `/api/**` traffic |
| **Keycloak** | http://localhost:8443 | admin console; `admin` / `admin` |
| Eureka dashboard | http://localhost:8761 | also proxied at http://localhost:8080/eureka |
| Config server | http://localhost:8888 | e.g. `/product-service/default` |
| Zipkin | http://localhost:9411 | distributed traces |
| Kafka | `localhost:29092` | host-side listener; `kafka:9092` in-network. Topics: `order.exchange`, `springCloudBus` |
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
user) still requires a token — and now a role.

### Roles

Roles come from the token's `realm_access.roles`. The gateway prepends `ROLE_`, so
the Keycloak realm role must be named `ADMIN`, never `ROLE_ADMIN`.

| routes | required |
|---|---|
| `GET /api/users/me`, `PUT`/`DELETE /api/users/me` | any valid token |
| `GET /api/users`, `GET`/`PUT`/`DELETE /api/users/{id}` | **`ADMIN`** |
| `POST`/`PUT`/`PATCH`/`DELETE /api/products/**` | **`ADMIN`** |
| everything else — catalogue reads, carts, orders | any valid token |

Order matters in `SecurityConfig`: the first matching rule wins, so
`/api/users/me` is declared **before** `/api/users/**`. Reversed, every customer
would be locked out of their own profile.

The `/me` routes exist because `/api/users/{id}` takes an id **from the caller**.
Until roles were enforced, any logged-in customer could read, edit or delete any
account — measured, not hypothetical: one user fetched another's name, phone and
street address with a `200`. The `/me` routes take no id at all; the only input is
the `X-User-ID` the gateway rewrites from the token, so authorization is
structural rather than a check that can be forgotten. That is the same shape the
cart and order endpoints have always had.

Not enforced anywhere: `CUSTOMER`. The catch-all is `authenticated()` rather than
`hasRole("CUSTOMER")` on purpose — an admin holds `ADMIN` and not necessarily
`CUSTOMER`, so the stricter rule would 403 admins out of shopping.

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

## Front end

React 19 + Vite, `react-oauth2-code-pkce`, Tailwind v4, react-router. It lives in
`frontend/` and runs on the Vite dev server only — **deliberately not a compose
service**. The origin is the thing that matters, and `npm run dev` already serves
it on the origin the gateway and Keycloak both trust.

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

The main stack must be up, and the Keycloak console steps above must be done, or
the login round trip fails at the token exchange.

### Environment variables

Every address the front end knows comes from **`frontend/.env`**, read through
`src/config.js`. This is a **second** `.env`, separate from the root one — the
root file configures the containers, this one configures the browser app. Create
it alongside `package.json`:

```dotenv
# ─── The gateway ───
# Every /api/** call goes here. Never to a service directly - product-service and
# the rest are not published to the host at all.
VITE_API_BASE_URL=http://localhost:8080

# ─── Keycloak ───
# The host must match cloud-gateway.yml's issuer-uri CHARACTER FOR CHARACTER: the
# gateway compares the token's "iss" claim as a plain string, which is why
# docker-compose pins KC_HOSTNAME to http://localhost:8443.
VITE_KEYCLOAK_URL=http://localhost:8443
VITE_KEYCLOAK_REALM=ecom-app

# ─── The login client ───
# The public PKCE client. NOT ecom-admin, which is confidential, server-to-server,
# and holds a secret that must never reach a browser.
VITE_KEYCLOAK_CLIENT_ID=oauth2-pkce
```

| variable | default if unset | what it points at |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | the gateway |
| `VITE_KEYCLOAK_URL` | `http://localhost:8443` | Keycloak's base URL |
| `VITE_KEYCLOAK_REALM` | `ecom-app` | realm name |
| `VITE_KEYCLOAK_CLIENT_ID` | `oauth2-pkce` | the public PKCE client |

**Every one of these has a fallback in `src/config.js`**, so a fresh clone with no
`.env` at all still runs against a default local stack. The file exists to make the
values explicit and overridable, not to make the app work.

Four things worth knowing:

- **It is gitignored.** The root `.gitignore` line `.env` matches at any depth, so
  `frontend/.env` is ignored along with the root one — which is why it is written
  out above rather than assumed present. Nothing in it is actually secret; it is
  ignored by inheritance, not by intent.
- **Only `VITE_`-prefixed variables reach the browser.** That is Vite refusing to
  bundle anything else, and it is a real safety property: put a client secret in
  this file without the prefix and it simply will not be exposed. Put one in *with*
  the prefix and it ships to every visitor — so never do that. The front end is a
  public client and holds no secret by design.
- **Substitution happens at build time, not runtime.** `import.meta.env.X` is
  replaced with a string literal when the bundle is built, so **editing `.env`
  needs a dev-server restart** — a browser reload will not pick it up. Confirmed:
  the built bundle contains the literal URLs and zero `import.meta.env` references.
- **The redirect URI is deliberately not here.** It is `window.location.origin`,
  read from the browser. It *must* equal the origin the page is actually served
  from or Keycloak rejects the redirect, and taking it from the browser makes the
  two impossible to disagree. If you change the port, change it in Keycloak's Valid
  redirect URIs and Web origins, and in the gateway's CORS bean — not here.

The port is pinned with `strictPort: true` in `vite.config.js`. Without it Vite
slides to 5174 when 5173 is busy, and every CORS allow-list — the gateway's bean
and Keycloak's Web origins — silently stops matching.

### Pages

| route | needs |
|---|---|
| `/` catalogue, `/signup` | nothing |
| `/products/:id`, `/cart`, `/orders`, `/profile` | a token |
| `/admin/products` — create / edit / delete | **`ADMIN`** |
| `/admin/users` — list / delete | **`ADMIN`** |

Admin links appear in the nav only for an `ADMIN` token, and `AdminRoute` guards
the routes.

> **The front-end role check is not security.** It decides what a user *sees*.
> What a user may *do* is decided by the gateway, against a signed token, on every
> request. Anyone can edit `tokenData` in devtools and reveal every admin screen —
> and every call those screens make still comes back `403`.

Two things the admin screens deliberately do not do. The Users table shows no
roles, because it cannot: `GET /api/users` returns profiles, and roles live only
in Keycloak. And you cannot delete your own account from it — that would leave a
live token whose `/me` is gone, and if you are the only admin, nobody can reach
the screen again.

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
| POST | `/api/products` | **`ADMIN`** — create a product |
| PUT | `/api/products/{id}` | **`ADMIN`** — update product by ID |
| DELETE | `/api/products/{id}` | **`ADMIN`** — **soft delete**, sets `active=false`, row remains |

### User service

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/users` | **public** — signup. Creates the Keycloak account **and** the Mongo profile, assigns realm role `CUSTOMER` |
| GET | `/api/users/me` | The caller's own profile — no id in the URL |
| PUT | `/api/users/me` | Update the caller's own profile |
| DELETE | `/api/users/me` | Delete the caller's own account |
| GET | `/api/users` | **`ADMIN`** — list every user |
| GET | `/api/users/{id}` | **`ADMIN`** — by **Keycloak user id** (the JWT `sub`), not a Mongo ObjectId |
| PUT | `/api/users/{id}` | **`ADMIN`** — update by ID |
| DELETE | `/api/users/{id}` | **`ADMIN`** — **hard delete**, removes the Mongo profile **and** the Keycloak account |

`POST /api/users` accepts `username`, `password`, `firstName`, `lastName`,
`email`, `phone`, `addressDto`. There is no `id` and no `role`: the id comes back
from Keycloak, and the role is always `CUSTOMER`. `password` is write-only — it is
accepted on the way in and never echoed back.

**`UserResponse` carries no `role`.** It used to, and the value was written once
at signup and never again — so promoting someone in the Keycloak console left the
profile still reporting `CUSTOMER` for an actual admin. Keycloak is the system of
record and nothing syncs backward, so a stored copy could only ever go stale. A
client that wants roles reads `realm_access.roles` from its own token.

**Delete removes both halves.** Mongo first, then Keycloak. Keycloak cannot join
the transaction either way, so the question is which failure is recoverable:
deleting Keycloak first and then failing leaves a profile nobody can log in as —
unreachable *and* undeletable through this endpoint, since `findById` still
succeeds but names a dead account. This order fails the other way, leaving an
account with no profile, which is the state signup already knows how to
compensate for and an admin can clear in the console. A failed Keycloak delete is
logged loudly and not rethrown — the profile really is gone, so reporting failure
would invite a retry that 404s.

A duplicate signup answers **409** with Keycloak's own message
(`{"detail":"User exists with same email"}`), not 502. Only a genuinely
unreachable Keycloak is a 502.

### Order service

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/carts` | Get the current user's cart |
| POST | `/api/carts` | Add an item to the cart |
| DELETE | `/api/carts/items/{productId}` | Remove an item from the cart |
| POST | `/api/orders` | Create an order from the cart, clears it — **no body** |
| GET | `/api/orders` | The caller's order history, newest first |

No `X-User-ID` column any more — see [Authentication](#authentication). The
gateway injects it from the token.

`GET /api/orders` answers `200 []` for a user who has never ordered — an empty
history is a successful answer to a valid question, not a 404. Like the cart
routes it takes no id, so a caller cannot ask for anyone else's orders.

**`productId` is a number everywhere.** It used to be a `String` in the cart DTOs
and a `Long` on the product, which meant `GET /api/carts` returned
`"productId":"1"` while `GET /api/products` returned `"id":1` — so
`product.id === cartItem.productId` was always false in JavaScript. It is now
`Long` from the DTO through the entity to the `bigint` column. A malformed id is
rejected by Jackson at the framework boundary with a **400**, before any service
code runs.

### Config-refresh demo endpoints — not routed

Two `@RefreshScope` demo endpoints exist for showing Spring Cloud Config
live-refresh:

| Endpoint | Service | prefix |
|---|---|---|
| `GET /api/product/demo/message` | product-service | **singular** |
| `GET /api/orders/demo/message` | order-service | **plural** |

Note the two differ — `ProductConfigDemoController` is `/api/product/demo` while
`OrderConfigDemoController` is `/api/orders/demo`. Neither is reachable through
the gateway (the product one because the gateway routes only `/api/products/**`;
the order one because the gateway route requires a token and these are demo
endpoints), and since the business services are not published to the host, they
are not reachable from your machine at all.

Reach them from inside the network:

```bash
docker run --rm --network ecom-network curlimages/curl -s \
  http://product-service:8081/api/product/demo/message
docker run --rm --network ecom-network curlimages/curl -s \
  http://order-service:8083/api/orders/demo/message
```

### Config refresh without restarting

Change a value in `configserver/src/main/resources/config/*.yml` — the directory
is bind-mounted, so config-server serves the edit immediately — then broadcast:

```bash
docker run --rm --network ecom-network curlimages/curl -s -X POST \
  http://product-service:9090/actuator/busrefresh          # 204 No Content
```

**Post it to any one service and every service applies it.** That is the whole
point of the bus: the endpoint publishes a `RefreshRemoteApplicationEvent` to the
`springCloudBus` Kafka topic, and every service is subscribed. Measured — the
command above was sent to product-service alone, and order-service picked up its
new value.

What happens on each receiving service, in order:

1. `RefreshListener` receives the event and calls `ContextRefresher.refresh()`.
2. The `Environment` is rebuilt, which **re-fetches config from config-server over
   HTTP** — you can see it in config-server's log as
   `Adding property source: Config resource 'file [/app/config/product-service.yml]'`.
3. Old and new property sources are diffed; an `EnvironmentChangeEvent` carries
   the changed keys, and `@ConfigurationProperties` beans are re-bound.
4. `RefreshScope.refreshAll()` **discards the cached instance** of every
   `@RefreshScope` bean. They are rebuilt lazily on next access, against the new
   `Environment` — which is when a `@Value` is re-resolved.

**The ApplicationContext is never closed.** The servlet container, connection
pools, JPA `EntityManagerFactory` and Kafka bindings all keep running untouched.
That is why it is not a restart.

**What does *not* refresh**, and this is the part that surprises people:

| refreshes | does not |
|---|---|
| `@Value` inside a `@RefreshScope` bean | `@Value` in a plain singleton — injected once at construction |
| `@ConfigurationProperties` beans (re-bound) | `server.port`, datasource URL — read once at startup |
| Resilience4j / rate-limit values read per call | Logback rolling policy — the appender is built at startup |
| | `spring.cloud.stream` bindings |

A plain `@Value` in a singleton silently keeps the old value. Nothing errors; the
refresh reports success and that one field is simply stale.

`details.md` §28 covers the mechanism in full, including the side effects — the
Eureka client bug this surfaced, the accumulating anonymous consumer groups, and
why a broadcast with no acknowledgement can apply to some services and not others.

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

Zipkin is also a **Grafana datasource**, so traces and logs sit in one place.
There is only ever **one** Zipkin — the container in the root `docker-compose.yml`
— and the observability stack does not add a second; `grafana` simply joined
`ecom-network` so the name resolves, the same way `prometheus` already does:

```yaml
# evaluate-prometheus/docker-compose.yaml — grafana
    networks: [loki, ecom-network]
# evaluate-prometheus/grafana/datasources/datasources.yml
  - name: Zipkin
    type: zipkin
    url: http://zipkin:9411
```

The payoff over Zipkin's own UI is `tracesToLogsV2`: clicking a span jumps to the
Loki logs carrying the same trace id, which Spring Boot already writes into every
line as `[<traceId>-<spanId>]`. That link needs the explicit `uid: loki` on the
Loki datasource — without one Grafana generates a random uid per install and the
link breaks silently.

### Metrics — Prometheus + Grafana

With the observability stack running:

- Prometheus targets — http://localhost:9090/targets (all jobs should be `UP`)
- Grafana — http://localhost:3000 (anonymous admin access enabled)

Services expose `/actuator/prometheus` on internal port 9090.

### Logs — files and rotation

Every service writes to `logs/<service-name>/<service-name>.log` on the host
(each container mounts its own subdirectory at `/app/logs`). Rotation is
configured once, in the shared config:

```yaml
logging:
  file:
    name: logs/${spring.application.name}.log
  logback:
    rollingpolicy:
      file-name-pattern: ${LOG_FILE}.%d{yyyy-MM-dd}.%i.log
      max-file-size: 5MB
      max-history: 7
      total-size-cap: 50MB
```

Reading those four lines:

| setting | effect |
|---|---|
| `file-name-pattern` | rolls **daily**; `%i` is the within-day counter used if 5MB is hit |
| `max-file-size` | 5MB — in practice never reached; the largest live log is ~200KB |
| `max-history` | keeps **7 days** (the unit comes from the date pattern, not from the number) |
| `total-size-cap` | deletes oldest-first if archives exceed 50MB per service |

Archives are plain `.log`, **not** `.gz`. Spring Boot's default pattern ends in
`.gz`, which Windows File Explorer cannot open — dropping the suffix keeps
archives double-clickable at the cost of ~14× the disk. Real usage is ~15MB for
7 days across all seven services, so the cap never trips.

The setting is duplicated in two files, and both are inside the config-server
module:

| file | applies to |
|---|---|
| `configserver/src/main/resources/application.yaml` | config-server itself |
| `configserver/src/main/resources/config/application.yml` | every other service |

The duplicate exists because config-server cannot fetch config from itself. A
rolling-policy change is **not** picked up by `/actuator/busrefresh` — Logback
builds its appender at startup, so it needs a restart.

### Logs — Loki

The same `logs/` tree is mounted into Alloy and shipped to Loki. Query in Grafana
by the `service_name` label:

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

# ── Spring Cloud Bus (a Kafka topic now, not a Rabbit exchange) ──
docker exec ecom_kafka kafka-topics --bootstrap-server localhost:9092 \
  --describe --topic springCloudBus
# One anonymous consumer group PER SERVICE PER RESTART - that is the fanout, and
# also why this list grows. See details.md §28.4.
docker exec ecom_kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list

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
An authorization outcome, not an error, so nothing is logged anywhere. Decode the
token at jwt.io and read `realm_access.roles`, or raise the gateway converter to
debug (`logging.level.com.ramesh.gateway.security=DEBUG`) for the
`Extracted roles for sub …` line. Three causes, in order of likelihood: the role
was never assigned; the token predates the assignment, because roles are baked in
at issue time and a full logout is needed to get a new one; or the realm role was
created as `ROLE_ADMIN` — the converter adds the prefix, so it must be plain
`ADMIN` or you get `ROLE_ROLE_ADMIN`, which matches nothing.

**Logged in as an admin but the nav shows no admin links**
The token has no `ADMIN`. Same three causes as above — usually the second: log out
fully rather than just reloading, since clearing local state leaves Keycloak's SSO
cookie and the next login silently returns the same session.

**Signup returns `409`, or `502`**
They mean different things now. **`409`** is a duplicate — the body carries
Keycloak's own message, `{"detail":"User exists with same email"}`. **`502`** is a
genuine upstream problem: `invalid_client` or `unauthorized_client` means
`KEYCLOAK_ADMIN_CLIENT_ID` / `KEYCLOAK_ADMIN_CLIENT_SECRET` do not match the
`ecom-admin` client; a `403` from the Admin API means the service account is
missing `manage-users` or `view-realm`; a `404` on the role means `CUSTOMER` was
never created in the realm. Those three details stay in the user-service log
rather than the response, because signup is the one anonymous route and the
message contains the in-network admin URL. `docker logs ecom_user | grep Keycloak`.

**The login page appears, the password works, and then nothing happens**
The redirect lands on `:5173/?code=…` and the app never gets a token. Almost
always Keycloak's **Web origins** — a separate field from redirect URIs, needed
only by browsers, so Postman never revealed it missing. See
[Keycloak setup](#keycloak-setup-one-time). Nothing appears in the Keycloak or
gateway logs, because the blocked token request never reached either; the browser
console is the only place it shows.

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

**An order call hangs instead of failing**
It should now abort at 5s: order-service sets `spring.http.clients.read-timeout:
5s` in `config/order-service.yml`. Before that there was no timeout at all, and
the circuit breaker does not supply one — `slow-call-duration-threshold`
classifies a call as slow *after it finishes*, it never interrupts one in flight,
and the `@CircuitBreaker` annotation applies no `TimeLimiter`. To see the timeout
work, use `docker pause ecom_product` (packets are dropped) rather than
`docker stop`, which refuses the connection instantly and fails fast either way.
Note the property is `spring.http.client`**`s`** — plural; the singular form is
deprecated since Boot 4.0.0 and binds with only a warning.

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
├── frontend/              # React + Vite SPA — NOT a compose service, `npm run dev`
│   ├── .env               #   VITE_* addresses; committed, nothing secret
│   └── src/
│       ├── config.js      #   every URL the app knows, in one place
│       ├── api/           #   one axios instance + interceptors
│       ├── context/       #   CartProvider, ToastProvider
│       ├── hooks/         #   useRoles — reads realm_access.roles
│       └── pages/         #   incl. AdminUsers, AdminProducts
├── evaluate-prometheus/   # Grafana + Prometheus + Loki + Alloy + Zipkin datasource
├── logs/                  # per-service log output — daily rotation, 7 days,
│                          #   plain .log archives (mounted into Alloy)
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
§26 covers the React front end and §27 the authorization work — roles enforced,
`/me`, one type for `productId`, and the admin UI — written plainer than the rest
and probably the best place to start. §28 retires RabbitMQ by moving Spring Cloud
Bus onto Kafka, and explains what a config refresh actually does to a running
JVM — including the side effects.
§25 is the one section that is not a change log entry: it explains the two
outbound-HTTP client shapes in the codebase — the discovery-aware
`@HttpExchange` clients in order-service versus the single-purpose Keycloak
client in user-service — and when to reach for each.
