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
| REST controllers | In progress |
| Kafka producers/consumers | Pending |
| WebSocket broadcasts | Pending |
| JWT authentication | Out of scope — handled by SSO team |

