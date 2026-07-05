# Proposal: Adopting Redis as the Real-Time Data Store for Signal of Service

**Author:** Cherm
**Project:** Signal of Service — Roadside Assistance (SOS) Platform
**Date:** July 4, 2026

## 1. Purpose

This proposal recommends adding **Redis** to the Signal of Service stack to handle high-frequency mechanic GPS location writes and nearby-mechanic lookups, and explains why Redis was chosen over two commonly suggested alternatives — **Firebase Realtime Database** and **Supabase Realtime**.

## 2. Terminology: "Real-Time Database" vs. What This System Needs

This section exists to head off an obvious question: if the ask was to find a real-time database for future scale, why does this proposal recommend Redis instead of a product literally named "Realtime Database" (Firebase) or "Realtime" (Supabase)?

"Real-time database" is a **product category** — a hosted service that bundles storage with automatic client push (write a value, subscribed clients are notified). Redis is a different category: an **in-memory data store**. But it is one of the most widely used engines *underneath* real-time systems in production — it's the standard backing layer for scaling WebSocket/Socket.io deployments across multiple servers, and it's used by companies like Discord and Slack for exactly this class of real-time, high-frequency data. Recommending Redis isn't a rejection of "get something for real-time at scale" — it's a more precise answer to it, because this system already built its own delivery layer (Kafka + WebSocket) instead of relying on a vendor's bundled one.

Concretely, at scale, Redis addresses two needs, not just one:

1. **Fast reads/writes for the GPS hot path** (the primary subject of this proposal — see Section 4/5).
2. **A shared broker for WebSocket fan-out across multiple app instances.** The current `WebSocketConfig` uses Spring's in-memory `enableSimpleBroker`, which only knows about clients connected to that specific instance. The moment this service runs on more than one instance (normal when scaling for traffic), a customer's WebSocket connection and the Kafka consumer processing their mechanic's location update can land on different instances — and the update silently never reaches them. Redis pub/sub is the standard fix for this, meaning the same Redis addition proposed here for the DB hot path can also close this scaling gap, rather than requiring a separate initiative later.

So Redis is not "instead of" real-time infrastructure — it is real-time infrastructure, positioned at the specific layer (data access + cross-instance broadcast) this system is missing, rather than the layer (client push) it already has.

## 3. Background

Signal of Service is a Spring Boot 4 / Java 21 microservice for a roadside assistance platform. Users submit breakdown requests ("SOS requests"); nearby mechanics bid on them; the accepted mechanic's live location is streamed to the customer until the job completes.

The system already has a complete, working real-time architecture:

- **PostgreSQL 16 + PostGIS 3.4** — system of record for all entities (`SosRequest`, `BidSos`, `Mechanic`, etc.), with Uber H3 (resolution 7) for coarse geo-bucketing and `ST_DWithin` for exact-radius mechanic search.
- **Apache Kafka** — event bus. Every domain transition (SOS created, bid placed/accepted/rejected, status change, mechanic GPS ping) is published as an event, with a dead-letter topic (`sos.dlq`) for failed processing.
- **WebSocket/STOMP** — pushes events to connected clients in real time (`/topic/sos/{sosId}/location`, `/queue/sos-updates`, `/queue/sos-new-request`).
- **OneSignal** — mobile push notifications, triggered off Kafka events via a dedicated consumer, so users are notified even when the app is closed.

This is a mature pipeline: producers, consumers, and WebSocket broadcasting are all implemented and marked complete in the project's own documentation.

## 4. Problem statement

The one part of the system without a dedicated solution is **write and query load on the hot path**: every mechanic GPS ping currently writes directly to Postgres (`Mechanic.currentLocation`, `currentH3Index`), the same database holding all transactional data (`SosRequest`, `BidSos`, `SosStatusHistory`). If mechanics ping every few seconds while active, this is the highest-frequency write in the system, landing on infrastructure shared with business-critical transactional writes. The same database also serves the "find nearby available mechanics" query on every new SOS request.

Neither of these is a delivery problem — delivery is already solved. It's a **fast read/write access problem**.

## 5. Options considered

### Option A — Firebase Realtime Database

Firebase Realtime DB is a NoSQL, JSON-tree database with built-in client subscriptions: write a value, subscribed clients are notified automatically. This is the same problem Kafka + WebSocket already solve in this system — introducing Firebase would duplicate delivery, not add capability. It also has no native geospatial query support (geohashing has to be implemented by hand), and its data model has no relationship to the relational schema (JPA entities, MapStruct mappers, PostGIS columns) this project already has. Adopting it would mean rewriting a completed data layer to gain a capability the system doesn't need.

### Option B — Supabase Realtime

Supabase Realtime is built on top of PostgreSQL logical replication — Supabase's database *is* Postgres. Migrating to it would not resolve the write-contention problem described above, because the underlying data store doesn't change; only the hosting provider would. It would also introduce a second Postgres instance to reconcile with the existing PostGIS setup, and, like Firebase, duplicates the delivery layer already provided by Kafka + WebSocket.

### Option C — Redis

Redis is an in-memory data store, not a delivery mechanism, which matches the actual gap: fast reads/writes and geospatial queries, sitting alongside Postgres rather than replacing anything.

- `GEOADD` / `GEOSEARCH BYRADIUS` provide the "nearest available mechanic" query natively, in-memory, without needing a hand-rolled geohash scheme (Firebase) or another relational database (Supabase).
- It's additive: no changes to the JPA entity model, MapStruct mappers, or existing controllers/repositories — only `MechanicServiceImpl`'s location-update and lookup paths change.
- It fits the existing self-hosted infrastructure pattern (Postgres and Kafka already run this way), rather than introducing an external managed vendor with its own SDK, auth model, and billing.

## 6. Recommendation

Adopt **Redis** as a write-through cache and geo-index for `Mechanic.currentLocation`, positioned alongside Postgres, not in place of it:

| Component | Role |
|---|---|
| PostgreSQL + PostGIS | System of record (unchanged) |
| Kafka | Event bus / delivery trigger (unchanged) |
| WebSocket/STOMP | Client push (unchanged) |
| **Redis (new)** | Hot-path GPS writes + nearest-mechanic geo queries |

This is the minimal change that addresses the actual bottleneck, without disturbing the parts of the system that already work.

## 7. Why not Firebase or Supabase — summary

| Criterion | Redis | Firebase Realtime DB | Supabase Realtime |
|---|---|---|---|
| Solves the actual problem (fast DB read/write) | Yes | No — solves delivery, which is already solved | No — still Postgres, same bottleneck |
| Native geospatial queries | Yes (`GEOADD`/`GEOSEARCH`) | No (manual geohashing) | Same as current Postgres (`ST_DWithin`) |
| Compatible with existing data model | Yes — additive only | No — different data model entirely | Compatible, but redundant |
| Avoids duplicating Kafka + WebSocket | Yes | No — overlapping delivery mechanism | No — overlapping delivery mechanism |
| Fits existing self-hosted infra pattern | Yes | No — external vendor | No — external vendor |

## 8. Implementation outline

1. Add `spring-boot-starter-data-redis` to `pom.xml`; add a Redis service to the infra repo's docker-compose, with `REDIS_HOST`/`REDIS_PORT` in `.env`.
2. Add a `RedisConfig` class exposing a `RedisTemplate` / geo operations bean.
3. Update `MechanicServiceImpl.updateLocation()` to `GEOADD` into Redis on every ping; retain a (less frequent) Postgres write for durability.
4. Update the mechanic-search path to query Redis via `GEOSEARCH BYRADIUS` for candidate IDs, then apply the existing specialization filter.
5. Handle staleness (mechanics going offline) via a short-TTL key per mechanic, cleaned up by a scheduled job.
6. Rehydrate the Redis geo set from Postgres on application startup, so a cold cache never means "no mechanics found."
7. Instrument write/query latency (Datadog) before and after, to confirm the change actually resolves the bottleneck it targets.

## 9. Open question

The team has not yet measured actual GPS ping frequency or concurrent active-mechanic count in production. This number determines urgency — instrumenting current Postgres write latency on `updateLocation()` should happen before implementation begins, so the decision is grounded in measured load rather than anticipated load.
