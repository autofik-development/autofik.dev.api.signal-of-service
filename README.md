# Signal of Service — SOS Roadside Assistance API

A Spring Boot microservice powering a **roadside assistance platform**. Users submit breakdown requests; nearby mechanics discover them, place bids, and the user picks one. Real-time status tracking is broadcast over WebSocket with events published to Kafka.

---

## How It Works

```
User submits SOS
       │
       ▼
┌─────────────────────────────────────────────────┐
│              SOS Request (PENDING)              │
│  problem type · GPS location · vehicle          │
│  broadcastRadiusKm (5–20 km, default 15 km)     │
└─────────────────────────────────────────────────┘
       │
       │  System finds nearby available mechanics
       │  using 2-stage geo-search:
       │
       │  Stage 1 ── H3 hex index (coarse, adaptive kRing)
       │  Stage 2 ── PostGIS exact radius filter
       │             (LEAST of mechanic service radius
       │              and SOS broadcastRadiusKm)
       │
       ▼
┌─────────────────────────────────────────────────┐
│           Mechanics receive SOS alert           │
│   Each mechanic submits a Bid (price)           │
│   Sortable by: NEAREST · CHEAPEST · TOP_RATED   │
└─────────────────────────────────────────────────┘
       │
       │  User reviews bids and accepts one
       │  All other bids auto-rejected
       │
       ▼
┌─────────────────────────────────────────────────┐
│         SOS Status Lifecycle (FSM)              │
│                                                 │
│  PENDING ──► ACCEPTED ──► ON_THE_WAY            │
│                               │                 │
│                            ARRIVED              │
│                               │                 │
│                 ┌─────────────┴──────────────┐  │
│                 │  Mechanic proposes COMPLETE │  │
│                 │  User approves / rejects    │  │
│                 └─────────────┬──────────────┘  │
│                               │                 │
│                           COMPLETED             │
│                                                 │
│  Any non-terminal state ──► CANCELLED           │
└─────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────┐
│               Job Complete                      │
│  User submits Feedback + Rating (1–5)           │
│  Mechanic rating average auto-recalculated      │
└─────────────────────────────────────────────────┘
```

---

## Domain Model

```
┌──────────┐        ┌─────────────┐        ┌──────────┐
│   User   │───────►│ SosRequest  │◄───────│ Vehicle  │
└──────────┘  1:N   └─────────────┘  N:1   └──────────┘
                           │
              ┌────────────┼──────────────────┐
              │            │            │      │
              ▼            ▼            ▼      ▼
         ┌────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
         │ BidSos │  │ SosStatus│  │ Feedback │  │ SosMedia │
         └────────┘  │ History  │  └──────────┘  └──────────┘
              │      └──────────┘        │
              │                          │ rates
              ▼                          ▼
        ┌──────────┐     ┌──────────────┐     ┌─────────────┐
        │ Mechanic │────►│ MechanicSkill│     │ MechanicRate│
        └──────────┘  1:N└──────────────┘ 1:1 └─────────────┘
              │                                      ▲
              └──────────────────────────────────────┘
                    upserted on every Feedback submit
```

`MechanicRate` is a cached aggregate — one row per mechanic storing `rating_avg` and `rating_count`. Upserted by `FeedbackServiceImpl` after each new feedback so rating lookups never need to aggregate over the full feedback history.

---

## Architecture

```
HTTP Request
     │
     ▼
┌─────────────┐
│  Controller │  REST layer — thin delegates only
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Service   │  Business logic, FSM enforcement, transactions
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Repository  │  Spring Data JPA + custom PostGIS/H3 queries
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────┐
│  PostgreSQL 16 + PostGIS 3.4        │  Spatial data, entities
└─────────────────────────────────────┘

Side channels:
┌─────────────┐     ┌───────────────────┐     ┌───────────────┐
│    Kafka    │     │  WebSocket/STOMP  │     │   OneSignal   │
│  (events)  │     │  (real-time push) │     │ (push notify) │
└─────────────┘     └───────────────────┘     └───────────────┘

Real-time hot path:
┌─────────────┐
│    Redis    │  GPS write throttle · pub/sub location relay · rate limiting
└─────────────┘
```

### Real-time Location Flow

```
Mechanic app
  PATCH /api/v1/mechanics/{id}/location?lat=&lng=&sosId=
       │
       ▼
MechanicServiceImpl.writeLocation()
  → Redis SET NX EX  mechanic:pg-sync:{id}  (throttle key, 10 s TTL)
  → if throttle key was new → save current_location + current_h3_index to Postgres
  → if Redis is down      → write to Postgres on every ping (fail-safe)
       │
       ▼
MechanicServiceImpl.publishLocation()  (only when sosId is known)
  → Redis PUBLISH  mechanic:loc:{sosId}  (primary — sub-millisecond)
  → on Redis failure → Kafka mechanic.location.updated  (fallback)
       │
       ├── MechanicLocationRedisListener → WebSocket /topic/sos/{sosId}/location
       └── MechanicLocationConsumer (Kafka fallback) → same WebSocket channel

Customer app — updates mechanic marker on Google Maps in real time
```

`current_location` in Postgres is the source of truth. Postgres is written at most once per 10 seconds per mechanic (throttled by Redis). When Redis is unavailable the throttle is bypassed and every ping goes straight to Postgres.

### Availability Search Flow

```
GET /api/v1/mechanics/available?lat=&lng=&problemType=&radiusKm=
       │
       ▼
MechanicServiceImpl.findAvailable()
  Stage 1 — H3 adaptive kRing B-tree index scan
              converts radiusKm → ring depth (ceil(radiusKm / 2.114) + 1)
              e.g. 15 km → k=8 → 217 hex cells
              fast text IN (...) query on current_h3_index, eliminates most of the table
  Stage 2 — PostGIS ST_DWithin exact filter
              respects each mechanic's service_radius_km
  Stage 3 — in-memory skill tag filter (only when problemType is provided)
              uses JOIN FETCH to avoid N+1 on skill tags
```

Postgres `current_location` is the source of truth for all availability searches. Redis is not involved.

`GET /api/v1/mechanics/{id}/location` also reads directly from Postgres `current_location`. Continuous updates during an active job still arrive via WebSocket — use this endpoint only for initial screen load or reconnect.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| Language | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| ORM | Spring Data JPA + Hibernate Spatial |
| Geo indexing | Uber H3 3.7.2 (resolution 7; adaptive kRing for both availability search and SOS fan-out) |
| Cache / pub/sub | Redis (GPS write throttle, pub/sub location relay, SOS create rate limiting) |
| Messaging | Apache Kafka (Confluent 7.6.0) |
| Real-time | WebSocket / STOMP |
| Push notifications | OneSignal (onesignal-java-client 5.8.1) |
| Mapping | MapStruct |
| Security | Spring Security (stateless, JWT validation — pending SSO team) |
| Maps / Geocoding | Google Maps Platform (Geocoding API + Distance Matrix API) |
| Media storage | AWS S3-compatible (MinIO) |

---

## Kafka Topics

All topics are keyed by `sosId` (or `recipientId` for notifications without a specific SOS):

| Topic | Trigger |
| :--- | :--- |
| `sos.created` | New SOS request submitted |
| `sos.bid.created` | Mechanic places a bid |
| `sos.bid.accepted` | User accepts a bid |
| `sos.bid.rejected` | Bid auto-rejected when another bid is accepted |
| `sos.bid.cancelled` | Mechanic withdraws a pending bid |
| `sos.status.updated` | SOS status advances |
| `sos.notification` | Push notification events — consumed by `NotificationConsumer`, delivered via OneSignal |
| `mechanic.location.updated` | Mechanic GPS ping — Kafka fallback when Redis pub/sub is unavailable; consumed by `MechanicLocationConsumer`, relayed to WebSocket |
| `sos.dlq` | Dead-letter queue for failed events |

---

## WebSocket Channels

Clients connect via STOMP over `/ws`. App destination prefix: `/app`.

| Channel | Type | Payload | Sent when |
| :--- | :--- | :--- | :--- |
| `/queue/sos-updates` | user-specific | `SosStatusUpdatedEvent` / `BidAcceptedEvent` | Status changes, bid accepted/rejected, completion flow |
| `/queue/sos-new-request` | user-specific | `SosNotificationEvent` | New SOS fans out to nearby mechanics |
| `/topic/sos/{sosId}/location` | broadcast | `MechanicLocationEvent` | Mechanic GPS ping while on a job |

---

## Input Validation

All GPS coordinates are restricted to **Cambodia only** (WGS-84 bounding box):

| Parameter | Min | Max |
| :--- | :--- | :--- |
| `latitude` | 10.41 | 14.69 |
| `longitude` | 102.5 | 107.63 |

Applies to: Create SOS, Create Mechanic, Update Mechanic Location, and Find Available Mechanics endpoints.

**`broadcastRadiusKm`** on Create SOS: optional integer, 5–20 km. Defaults to 15 km internally when omitted.

---

## Rate Limiting & Guards

| Guard | Behaviour |
| :--- | :--- |
| Active SOS check | A user cannot submit a new SOS while one is already in `PENDING`, `ACCEPTED`, `ON_THE_WAY`, or `ARRIVED` state. Returns **409 Conflict**. |
| Create cooldown | After any SOS creation, the same user must wait **60 seconds** before creating another. Enforced via a Redis `SET NX EX` key. Returns **429 Too Many Requests**. |

---

## Getting Started

### Prerequisites

- Java 21
- Docker + Docker Compose
- Maven (or use `./mvnw`)

### Setup

```bash
# 1. Copy environment file and fill in values
cp .env.example .env

# 2. Start PostgreSQL + Kafka + Redis (managed in the infra repo)
docker compose up -d

# 3. Run the application
./mvnw spring-boot:run
```

The app starts on the port set by `SERVER_PORT` in `.env`.

### Environment Variables

| Variable | Description |
| :--- | :--- |
| `DB_HOST` | PostgreSQL host |
| `DB_PORT` | PostgreSQL port (default `5432`) |
| `DB_NAME` | Database name |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address (default `localhost:9092`) |
| `REDIS_HOST` | Redis host (default `localhost`) |
| `REDIS_PORT` | Redis port (default `6379`) |
| `WEBSOCKET_ALLOWED_ORIGINS` | Allowed WebSocket origins (default `*` — restrict to your domain in production, e.g. `https://*.autofik.com`) |
| `GOOGLE_MAPS_API_KEY` | Google Maps API key — see [Google Maps Setup](#google-maps-setup) below |
| `ONESIGNAL_APP_ID` | OneSignal app ID — found in OneSignal Dashboard → Settings → Keys & IDs |
| `ONESIGNAL_API_KEY` | OneSignal REST API key — same location as above |
| `S3_ENDPOINT` | Object storage endpoint for SOS media. Points at a self-hosted MinIO server (S3-compatible) |
| `S3_ACCESS_KEY` | Access key for the object storage above |
| `S3_SECRET_KEY` | Secret key for the object storage above |
| `S3_REGION` | Required by AWS SDK SigV4 signing — must be a non-empty string (e.g. `us-east-1`) even against MinIO |
| `S3_BUCKET_NAME` | Bucket name where SOS media objects are stored |
| `SERVER_PORT` | HTTP port the app listens on |

### Google Maps Setup

Two Google Maps Platform services are used, both in `GoogleMapsServiceImpl`:

| Service | Used by | Purpose |
| :--- | :--- | :--- |
| **Geocoding API** | `SosService.create()` | Reverse-geocodes the SOS GPS coordinates into a human-readable `locationAddress` |
| **Distance Matrix API** | `BidService.create()` | Computes ETA and distance between a mechanic's `currentLocation` and the SOS location |

Both calls **fail soft** — if the API key is missing or the request errors, the service catches, logs a `WARN`, and returns `null`. The request still succeeds; you just lose the address/distance enrichment.

**Where to get the key:**

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create (or pick) a project.
2. Enable **billing** — required even within the free tier.
3. In **APIs & Services → Library**, enable **Geocoding API** and **Distance Matrix API**.
4. In **APIs & Services → Credentials**, create an API key and paste it into `.env` as `GOOGLE_MAPS_API_KEY`.

### OneSignal Setup

OneSignal delivers push notifications to the mechanic and user mobile apps.

The mobile app must call `OneSignal.login(userId)` with the user's UUID on login — this registers the device under that `external_id`, which is what this service uses to target push delivery.

**Where to get the keys:**

1. Create an app at [onesignal.com](https://onesignal.com).
2. Go to **Settings → Keys & IDs**.
3. Copy **App ID** → `ONESIGNAL_APP_ID`.
4. Copy **REST API Key** → `ONESIGNAL_API_KEY`.

### Build & Test

```bash
# Build fat JAR (skip tests)
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=SosServiceImplTest

# Run a single test method
./mvnw test -Dtest=SosServiceImplTest#create_validRequest_buildsPointAndSaves
```

---

## Project Status

| Layer | Status |
| :--- | :--- |
| Domain entities | Complete |
| DTOs & MapStruct mappers | Complete |
| JPA repositories | Complete |
| Service layer | Complete — SOS, Bid, Mechanic, User, Feedback, SosMedia |
| REST controllers | Complete — User, Mechanic, SOS, Bid, Feedback, SosMedia. No `VehicleController` (vehicle rows inserted manually) and no `SpecializationController` (seeded via SQL by design) |
| Kafka producers | Complete — `SosEventProducer` covers SOS, bid, status, location, and notification events |
| Kafka consumers | Complete — `MechanicLocationConsumer` relays GPS pings to WebSocket; `NotificationConsumer` delivers push via OneSignal |
| Real-time location | Complete — mechanic GPS written to Postgres (source of truth), throttled to once per 10 s via Redis. Location events published via Redis pub/sub (primary) with Kafka fallback to WebSocket |
| Availability search | Complete — H3 adaptive kRing B-tree scan (Stage 1) + PostGIS ST_DWithin (Stage 2) + in-memory skill filter (Stage 3). Postgres `current_location` is the source of truth; Redis is not involved |
| WebSocket broadcasts | Complete — bid acceptance, status changes, location streaming, new SOS fan-out to nearby mechanics |
| Push notifications | Complete — OneSignal push for bid received, bid accepted/rejected/cancelled, status transitions, completion flow |
| SOS media (photo/video) | Complete — upload and retrieve via S3-compatible storage |
| Input validation | Complete — Cambodia bounding box on all lat/lng inputs; `broadcastRadiusKm` 5–20 km; rate limiting (60 s cooldown + active SOS guard) |
| JWT validation | Pending — token issued by SSO service; this service validates only |
