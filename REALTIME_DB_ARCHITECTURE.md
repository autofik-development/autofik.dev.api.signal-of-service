# Real-Time Data Layer — Does Signal of Service Need a Dedicated Real-Time Database?

## Context and goals

Signal of Service has three real-time requirements today:

1. Mechanic GPS location must reach the customer's app while a job is active (`/topic/sos/{sosId}/location`).
2. Bid/status changes must reach the relevant user or mechanic immediately (`/queue/sos-updates`).
3. A new SOS must fan out to nearby available mechanics as soon as it's created (`/queue/sos-new-request`).
4. "Nearby available mechanics" must be queryable fast enough to serve requests 1–3 mentioned above.

This doc answers the question raised during setup — whether to adopt a dedicated real-time database (Firebase Realtime DB, Supabase Realtime, etc.) — against what's actually implemented in this repo.

## Current design

```
Mechanic app
  PATCH /api/mechanics/{id}/location?lat=&lng=
       │
       ▼
MechanicServiceImpl.updateLocation()
  → writes currentLocation + currentH3Index to Postgres (source of truth)
  → publishes MechanicLocationEvent to Kafka (mechanic.location.updated)
       │
       ▼
MechanicLocationConsumer (@KafkaListener)
  → relays to WebSocket /topic/sos/{sosId}/location via SimpMessagingTemplate
       │
       ▼
Customer app — live marker update
```

Storage: PostgreSQL 16 + PostGIS 3.4, with Uber H3 (resolution 7) for coarse geo bucketing and `ST_DWithin` for the exact radius check. Delivery: Kafka as the event bus, STOMP/WebSocket as the client push transport. There is no dedicated real-time database in the stack — Postgres is the only datastore, and "real-time" here means an event pipeline (Kafka → WebSocket), not a database with built-in change-stream subscriptions.

## Do you need a real-time database (Firebase/Supabase-style)?

**Not for the delivery problem.** Products like Firebase Realtime DB or Supabase Realtime exist to solve exactly what Kafka + WebSocket/STOMP already solve here: pushing a change to subscribed clients without polling. Bolting one on top of the current Kafka/WebSocket pipeline would duplicate that capability, not improve it — you'd be running two real-time delivery mechanisms side by side. Kafka also buys you things those products don't: durable replay, the `sos.dlq` dead-letter topic, and multiple independent consumers (`MechanicLocationConsumer`, `NotificationConsumer`) off the same event.

**Possibly, for one specific hot path: mechanic GPS writes.** Every GPS ping currently does a transactional write to Postgres (`currentLocation`, `currentH3Index`) before anything else happens. If mechanics ping every few seconds while on an active job, this is the highest-frequency write in the system, and it's landing on the same database that holds `SosRequest`, `BidSos`, and `SosStatusHistory` — your actual transactional data. At low mechanic counts this is fine. It becomes a candidate for a change once ping volume is high enough to show up as write contention or latency on that same Postgres instance.

## Recommendation

- **Keep Postgres + PostGIS as the system of record.** The H3 → `ST_DWithin` → specialization funnel in `MechanicRepository`/`MechanicServiceImpl.findAvailable()` is a solid design and doesn't need replacing.
- **Keep Kafka + WebSocket as the real-time delivery layer.** Don't introduce a second real-time push mechanism — there's nothing it would add.
- **If GPS write volume becomes a measurable problem**, introduce Redis as a write-through cache specifically for `Mechanic.currentLocation`/`currentH3Index` — not as a replacement for Postgres, but to absorb high-frequency location writes off the transactional path. Redis's `GEOADD`/`GEOSEARCH` commands can also serve the "nearest mechanic" lookup faster than `ST_DWithin` at high query volume, with Postgres periodically reconciled from the cache (or vice versa, cache-aside on read).
- **Don't add this speculatively.** There's no evidence yet (from Datadog or otherwise) that Postgres write latency on `updateLocation()` is a bottleneck. Instrument it first — this is exactly the kind of decision that should be made from load data, not anticipated load.

## Trade-offs if Redis is added later

| | Benefit | Cost |
|---|---|---|
| Redis geo cache for location | Removes high-frequency writes from Postgres; faster nearby-mechanic queries at scale | New operational dependency; cache/DB consistency to manage; another moving part in `MechanicServiceImpl` |

## Open question to resolve before acting

What's the actual GPS ping frequency and concurrent active-mechanic count expected in production? That number determines whether this is a next-sprint concern or a "revisit in six months" one.
