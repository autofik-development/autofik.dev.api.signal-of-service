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
│  broadcastRadiusKm (5–30 km, default 15 km)     │
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
│referrer_ │  1:N   └─────────────┘  N:1   └──────────┘
│  code    │               │
└──────────┘  ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
         ┌────────┐  ┌──────────┐  ┌──────────┐
         │ BidSos │  │ SosStatus│  │ Feedback │
         └────────┘  │ History  │  └──────────┘
              │      └──────────┘
              │
              ▼
        ┌──────────┐     ┌──────────────────────┐
        │ Mechanic │────►│ MechanicSpecialization│
        │referrer_ │     └──────────────────────┘
        │  code    │              │
        └──────────┘              ▼
                          ┌──────────────┐
                          │Specialization│
                          └──────────────┘
```

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
│    Redis    │  GEO index · pub/sub relay · GPS throttle · rate limiting
└─────────────┘
```

### Real-time Location Flow

```
Mechanic app
  PATCH /api/mechanics/{id}/location?lat=&lng=
       │
       ▼
MechanicServiceImpl.writeLocation()
  → Redis GEOADD  mechanics:geo  (every ping — live GEO index)
  → Redis ZADD    mechanics:lastseen  (for staleness eviction)
  → Redis SET NX EX  mechanic:pg-sync:{id}  (throttle key, 10 s TTL)
  → if throttle key was new → save to Postgres (snapshot, max 1×/10 s)
       │
       ▼
MechanicServiceImpl.publishLocation()
  → Redis PUBLISH  mechanic:loc:{sosId}  (primary — sub-millisecond)
  → on Redis failure → Kafka mechanic.location.updated  (fallback)
       │
       ├── Redis subscriber → WebSocket /topic/sos/{sosId}/location
       └── MechanicLocationConsumer (Kafka fallback) → same WebSocket channel

Customer app — updates mechanic marker on Google Maps in real time
```

### Availability Search Flow

```
GET /api/mechanics/available?lat=&lng=&problemType=
       │
       ▼
MechanicServiceImpl.findAvailable()
  Stage 1 — H3 kRing(4) B-tree index scan
              filters by current_h3_index, eliminates most of the table
  Stage 2 — PostGIS ST_DWithin exact filter
              respects each mechanic's service_radius_km
  Stage 3 — in-memory skill tag filter (only when problemType is provided)
              uses JOIN FETCH to avoid N+1 on skill tags
```

`current_location` in Postgres is the source of truth. Redis is not involved in availability search.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| Language | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| ORM | Spring Data JPA + Hibernate Spatial |
| Geo indexing | Uber H3 3.7.2 (resolution 7; availability search k=4, SOS fan-out adaptive kRing) |
| Cache / geo index | Redis (GEO index, pub/sub relay, GPS write throttle, rate limiting) |
| Messaging | Apache Kafka (Confluent 7.6.0) |
| Real-time | WebSocket / STOMP |
| Push notifications | OneSignal (onesignal-java-client 5.8.1) |
| Mapping | MapStruct |
| Security | Spring Security (BCrypt, stateless JWT — pending) |
| Maps / Geocoding | Google Maps Platform (Geocoding API + Distance Matrix API) |
| Media storage | AWS S3-compatible (MinIO) |

---

## Kafka Topics

All topics are prefixed `sos.` and keyed by `sosId`:

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
| `/queue/sos-updates` | user-specific | `SosStatusUpdatedEvent` / `BidAcceptedEvent` | Status changes, bid accepted/rejected |
| `/queue/sos-new-request` | user-specific | `SosNotificationEvent` | New SOS fans out to nearby mechanics |
| `/topic/sos/{sosId}/location` | broadcast | `MechanicLocationEvent` | Mechanic GPS ping while on a job |

---

## Input Validation

All GPS coordinates are restricted to **Cambodia only** (WGS-84 bounding box):

| Parameter | Min | Max |
| :--- | :--- | :--- |
| `latitude` | 10.41 | 14.69 |
| `longitude` | 102.5 | 107.63 |

Applies to both the Create SOS and Create Mechanic endpoints.

**`broadcastRadiusKm`** on Create SOS: optional integer, 5–30 km. Defaults to 15 km internally when omitted.

---

## Rate Limiting & Guards

| Guard | Behaviour |
| :--- | :--- |
| Active SOS check | A user cannot submit a new SOS while one is already in `PENDING`, `ACCEPTED`, `ON_THE_WAY`, or `ARRIVED` state. Returns **409 Conflict**. |
| Create cooldown | After any SOS creation (including cancelled ones), the same user must wait **60 seconds** before creating another. Enforced via a Redis `SET NX EX` key. Returns **429 Too Many Requests**. |

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

The app starts on `http://localhost:28080` (configurable via `SERVER_PORT`).

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
| `GOOGLE_MAPS_API_KEY` | Google Maps API key — see [Google Maps Setup](#google-maps-setup) below |
| `ONESIGNAL_APP_ID` | OneSignal app ID — found in OneSignal Dashboard → Settings → Keys & IDs |
| `ONESIGNAL_API_KEY` | OneSignal REST API key — same location as above |
| `S3_ENDPOINT` | Object storage endpoint for SOS media. Points at a self-hosted MinIO server (S3-compatible) |
| `S3_ACCESS_KEY` | Access key for the object storage above |
| `S3_SECRET_KEY` | Secret key for the object storage above |
| `S3_REGION` | Required by AWS SDK SigV4 signing — must be a non-empty string (e.g. `us-east-1`) even against MinIO |
| `S3_BUCKET_NAME` | Bucket name where SOS media objects are stored |
| `SERVER_PORT` | HTTP port (default `28080`) |

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
| Real-time location | Complete — Redis GEO index is the primary store; Postgres synced at most once per 10 s. Location events published via Redis pub/sub (primary) with Kafka fallback. `GET /api/mechanics/{id}/location` reads Redis first, falls back to Postgres snapshot |
| Availability search | Complete — Redis GEOSEARCH primary (sub-millisecond); H3 + PostGIS two-stage fallback when Redis is unavailable |
| WebSocket broadcasts | Complete — bid acceptance, status changes, location streaming, new SOS fan-out to nearby mechanics |
| Push notifications | Complete — OneSignal push for bid received, bid accepted/rejected/cancelled, status transitions, completion flow |
| SOS media (photo/video) | Complete — upload, retrieve, delete via S3-compatible storage |
| Input validation | Complete — Cambodia bounding box on all lat/lng inputs; `broadcastRadiusKm` 5–30 km; rate limiting (60 s cooldown + active SOS guard) |
| JWT validation | Pending — token issued by SSO service; this service validates only |
