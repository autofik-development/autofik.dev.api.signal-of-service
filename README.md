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
└─────────────────────────────────────────────────┘
       │
       │  System finds nearby available mechanics
       │  using 3-stage geo-search:
       │
       │  Stage 1 ── H3 hex index (coarse, ~10 km)
       │  Stage 2 ── PostGIS exact radius filter
       │  Stage 3 ── Specialization match (in-memory)
       │
       ▼
┌─────────────────────────────────────────────────┐
│           Mechanics receive SOS alert           │
│   Each mechanic can submit a Bid (price + note) │
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
│                           COMPLETED             │
│                                                 │
│  Any non-terminal state ──► CANCELLED           │
└─────────────────────────────────────────────────┘
       │
       │  Status changes are two-step:
       │  Mechanic proposes → User approves
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
              ┌────────────┼────────────┐
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
        └──────────┘     └──────────────────────┘
        (GPS Point,              │
         H3 index,               ▼
         isAvailable)    ┌──────────────┐
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
┌─────────────┐     ┌───────────────────┐
│    Kafka    │     │  WebSocket/STOMP  │
│  (events)   │     │  (real-time push) │
└─────────────┘     └───────────────────┘
```

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| Language | Java 21 |
| Framework | Spring Boot 4.0.6 |
| Database | PostgreSQL 16 + PostGIS 3.4 |
| ORM | Spring Data JPA + Hibernate Spatial |
| Geo indexing | Uber H3 3.7.2 (resolution 7, k=4 ≈ 10 km) |
| Messaging | Apache Kafka (Confluent 7.6.0) |
| Real-time | WebSocket / STOMP |
| Mapping | MapStruct |
| Security | Spring Security (BCrypt, stateless JWT — pending) |

---

## Kafka Topics

All topics are prefixed `sos.` and keyed by `sosId`:

| Topic | Trigger |
| :--- | :--- |
| `sos.created` | New SOS request submitted |
| `sos.bid.created` | Mechanic places a bid |
| `sos.bid.accepted` | User accepts a bid |
| `sos.bid.rejected` | Bid auto-rejected |
| `sos.status.updated` | SOS status advances |
| `sos.dlq` | Dead-letter for failed events |

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

# 2. Start PostgreSQL + Kafka
docker compose up -d

# 3. Run the application
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080` (configurable via `SERVER_PORT`).

### Environment Variables

| Variable | Description |
| :--- | :--- |
| `DB_HOST` | PostgreSQL host |
| `DB_PORT` | PostgreSQL port (default 5432) |
| `DB_NAME` | Database name |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address |
| `GOOGLE_MAPS_API_KEY` | Google Maps API key — see [Google Maps Setup](#google-maps-setup) below. |
| `S3_ENDPOINT` | Object storage endpoint for SOS media (photo/video uploads). Points at a self-hosted MinIO server, **not** a real AWS region — the app uses the AWS SDK v2 against MinIO's S3-compatible API. |
| `S3_ACCESS_KEY` | Access key for the object storage above. |
| `S3_SECRET_KEY` | Secret key for the object storage above. |
| `S3_REGION` | Required by the AWS SDK's request signing (SigV4) even against MinIO, which ignores the actual value — **must be a non-empty string** (e.g. `us-east-1`) or the app fails to start. |
| `S3_BUCKET_NAME` | Bucket name where SOS media objects are stored. |

### Google Maps Setup

Two Google Maps Platform services are used, both in `GoogleMapsServiceImpl`:

| Service | Used by | Purpose |
| :--- | :--- | :--- |
| **Geocoding API** | `SosService.create()` | Reverse-geocodes the SOS request's GPS coordinates into a human-readable `locationAddress` |
| **Distance Matrix API** | `BidService.create()` | Computes ETA/distance text between a mechanic's `currentLocation` and the SOS location, shown on each bid |

Both calls **fail soft** — if the API key is missing, invalid, or the request errors out, `GoogleMapsServiceImpl` catches the exception, logs a `WARN`, and returns `null`. The request still succeeds; you just lose the address/distance enrichment on that field.

**Where to get the key:**

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create (or pick) a project.
2. Enable **billing** on that project — required even within the free tier; geocoding/distance calls return `REQUEST_DENIED` without it. If you hit *"You have reached the limit of projects on which you can enable billing"*, either reuse an existing billed project or disable billing on an old unused one first.
3. In **APIs & Services → Library**, enable:
   - **Geocoding API**
   - **Distance Matrix API**
4. In **APIs & Services → Credentials**, create an API key and paste it into `.env` as `GOOGLE_MAPS_API_KEY`.

### Build & Test

```bash
# Build fat JAR (skip tests)
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=SosServiceImplTest
```

---

## Project Status

| Layer | Status |
| :--- | :--- |
| Domain entities | Complete |
| DTOs & MapStruct mappers | Complete |
| JPA repositories | Complete |
| Service layer (business logic) | Complete |
| REST controllers | Complete — User, Mechanic, SOS, Bid, Feedback (CRUD + list-all + status lifecycle). No `VehicleController` yet (`createSos` needs a manually-inserted vehicle row) and no `SpecializationController` (specialization data is seeded directly via SQL, by design — schema not yet stable) |
| Kafka producers/consumers | Mostly complete — `SosEventProducer` covers SOS/bid/status/notification events. **Gap**: `MechanicLocationConsumer` listens on `mechanic.location.updated`, but nothing publishes to it yet — `MechanicServiceImpl.updateLocation()` only writes to Postgres. Needs `PATCH /api/mechanics/{id}/location` to know which `sosId` to broadcast to before this can be wired up (deferred to a future PR) |
| WebSocket broadcasts | Partial — real-time pushes work for bid acceptance and SOS status approval (`SimpMessagingTemplate`, called directly from the service layer). The mechanic-location relay (`MechanicLocationConsumer` → `/topic/sos/{sosId}/location`) exists but is currently unreachable, since the Kafka gap above means it never fires |
| SOS media (photo/video) | Partial — `SosMedia` entity + `MediaType` enum + relation to `SosRequest` exist; repository, DTOs, service, and controller not yet built |
| JWT validation | Pending — token issued by SSO team, this service validates only |

