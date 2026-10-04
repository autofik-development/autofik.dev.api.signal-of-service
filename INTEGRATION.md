# SOS service: integration contract

For the mobile team, the internal gateway/SSO team and anyone consuming SOS events. This replaces the old WebSocket guide and `api-tests.http`, which described headers the service no longer reads.

Written from the code on 2026-10-04 (controllers, DTOs, security, Kafka and STOMP classes). Where a behaviour is a known limitation it is listed in [Known gaps](#known-gaps) instead of being hidden.

## 1. Identity: how to call the service

The service does **not** validate tokens. The internal gateway verifies the caller and forwards two JSON headers; SOS trusts them.

| Header | Sent for | Shape |
|---|---|---|
| `user-context` | the customer app | `{"user":{"uuid":"<uuid>","role":"USER","deviceId":"…","otpVerify":true,"profileVerify":true,"tokenStatus":"active","isLogout":false}}` |
| `mechanic-context` | the mechanic app | `{"mechanic":{"uuid":"<uuid>","role":"MECHANIC","deviceId":"…","otpVerify":true,"profileVerify":true,"tokenStatus":"active","isLogout":false}}` |
| `service-code` | optional | which internal service proxied the call; logged only |
| `x-forwarded-for`, `user-agent` | optional | logs only |

Rules (`MicroserviceTokenSdk`, `CallerRole`):
- **`X-User-Id` / `X-Mechanic-Id` are ignored.** Sending only those gives 401.
- Unknown JSON fields are ignored, so the gateway can add fields.
- Roles: `USER`, `CUSTOM`, `CUSTOMER` are a driver; `MECHANIC`, `GARAGE` are a mechanic. Any other role gives 403. A `mechanic-context` that names a driver role gives 403.
- `otpVerify` and `profileVerify` must both be exactly `true`, otherwise 403 "User profile is not fully verified". A missing key counts as false.
- `isLogout: true`, or `tokenStatus` of `unauth`, `expired` or `failed`, gives 401. (Other `tokenStatus` values pass; the gateway should only send the values above for invalid sessions.)
- Both headers present with different uuids gives 401 "Conflicting identity".
- Malformed JSON, or a non-UUID `uuid`, is treated as no identity: 401.
- **Request bodies still carry `userId` / `mechanicId` and they must equal the header uuid** (otherwise 403). They are required (`@NotNull`) until the contract is relaxed.

**Security note for the gateway team:** the headers are unsigned. The gateway must strip any client-supplied `user-context` / `mechanic-context` and the SOS port must be reachable only from the gateway network.

## 2. Conventions

- Base path `/api/v1`. JSON in and out. Multipart for media.
- Ids are UUIDs. Coordinates are WGS-84; **Cambodia only**: latitude 10.41–14.69, longitude 102.5–107.63 (400 outside).
- Error body, every error including auth:

```json
{"status": 409, "error": "Conflict", "message": "You already have an active SOS request",
 "timestamp": "Oct 4 2026, 10:05:00 AM", "details": {"field": "message"}}
```
`details` appears only for validation errors (400).

| Status | Meaning |
|---|---|
| 400 | validation, bad enum/UUID/JSON, bad parameter |
| 401 | no usable identity, logged out or expired session |
| 403 | wrong role, unverified profile, not your resource |
| 404 | not found, or not visible to you |
| 405 / 406 / 415 | method, accept header, media type |
| 409 | invalid state (wrong transition, active SOS exists, duplicate bid or feedback) |
| 429 | SOS create cooldown (default 60 s) or rebroadcast limit (3). No `Retry-After` |
| 503 | Medusa unreachable, database pool exhausted (sends `Retry-After: 5`) |
| 500 | unexpected |

- **Timestamps differ by surface (known inconsistency):** REST responses use `MMM d yyyy, h:mm:ss a` with no zone (server local time); WebSocket events use ISO local date-time with no zone; the location event uses an ISO offset date-time at `+07:00`; Kafka uses the formats in §6. `asOf` on `GET /mechanics/{id}/location` is the time of the read, not of the GPS fix.
- Lists that return `Page` accept `page`, `size` (default 20, not capped) and `sort`.

## 3. Status machine

`PENDING → ACCEPTED → ON_THE_WAY → ARRIVED → IN_PROGRESS → COMPLETED`; `CANCELLED` and `EXPIRED` as below.

| From | To | Who and how |
|---|---|---|
| (new) | PENDING | customer `POST /sos` |
| PENDING | ACCEPTED | customer accepts a bid (`PATCH /bids/{id}/status`) |
| PENDING | PENDING | automatic: if the bidding window (default 60 s) ends with **no bids at all**, the radius widens by 5 km (max 35) and the SOS is broadcast again, up to 3 times |
| PENDING | EXPIRED | window ends with bids but none accepted, or the automatic retries are used up |
| EXPIRED | PENDING | customer `POST /sos/{id}/rebroadcast` (max 3) |
| ACCEPTED | ON_THE_WAY, then ARRIVED, then IN_PROGRESS | assigned mechanic `PATCH /sos/{id}/status?toStatus=`. ARRIVED requires the mechanic within 50 m by road |
| IN_PROGRESS | COMPLETED | two steps: mechanic `POST /sos/{id}/completion`, then the customer approves with `PATCH /sos/completion`. **If the customer does not answer within 2 minutes (configurable) the request is approved automatically** and the job completes |
| any non-terminal | CANCELLED | customer `PATCH /sos/{id}/cancel` |

`COMPLETED` and `CANCELLED` are terminal. There is no mechanic-side cancel. A customer may have only one active SOS (`PENDING`, `ACCEPTED`, `ON_THE_WAY`, `ARRIVED`, `IN_PROGRESS`): a second create returns 409.

## 4. REST endpoints

"Customer" means a `user-context` caller; "mechanic" means a `mechanic-context` caller. Every endpoint can also return 401, 403 and 500.

### SOS
| Method and path | Who | Request | Response | Errors |
|---|---|---|---|---|
| `POST /sos` | customer | `CreateSosRequest` | 201 `SosRequestResponse` | 400, 404 (user, vehicle or service group), 409 active SOS, 429 cooldown, 503 Medusa |
| `GET /sos?status=&page=&size=&sort=` | any | none | `Page<SosRequestResponse>` of the caller's own SOS | 400 |
| `GET /sos/{sosId}` | owner, a bidder, or an eligible mechanic while bidding is open | none | `SosRequestResponse`. For the owner, while the SOS is `IN_PROGRESS` and a completion request is waiting, `pendingCompletionHistoryId` holds the `historyId` to approve and `pendingCompletionApproveBy` is the time it will be approved automatically (use it for the countdown); both are null otherwise and never shown to mechanics | 404 |
| `GET /sos/{sosId}/nearby-mechanics` | same visibility | none | `MechanicResponse[]` | 404 |
| `PATCH /sos/{sosId}/status?toStatus=ON_THE_WAY\|ARRIVED\|IN_PROGRESS` | assigned mechanic | none | `SosRequestResponse` | 400, 404, 409 (bad transition, or over 50 m on ARRIVED) |
| `POST /sos/{sosId}/completion` | assigned mechanic | `ProposeStatusChangeRequest` (`toStatus` must be `COMPLETED`) | 201 `SosStatusHistoryResponse` (`id` is the `historyId`). **Idempotent while a request is waiting:** a repeat returns the same row and sends no second push | 400, 404, 409 |
| `PATCH /sos/completion` | customer | `ApproveStatusChangeRequest` | `SosRequestResponse` | 404, 409 |
| `PATCH /sos/{sosId}/cancel` | owner | none | `SosRequestResponse` | 404, 409 |
| `POST /sos/{sosId}/rebroadcast` | owner | none | `SosRequestResponse` | 404, 409, 429 |

`CreateSosRequest`: `userId`, `serviceGroupId` (Medusa id; the service derives `problemType` from it), `description` (optional), `latitude`, `longitude`, `vehicleId`, `broadcastRadiusKm` (optional, 5–20, default 15). **The customer's user and vehicle must already exist locally** (see Known gaps).

### Bids
| Method and path | Who | Request | Response |
|---|---|---|---|
| `POST /bids` | mechanic | `{mechanicId, sosId, price}` (price at least 0.01) | 201 `BidSosResponse` |
| `GET /bids/sos/{sosId}?sortBy=NEAREST\|CHEAPEST\|TOP_RATED` | owner sees all; others only their own | | `BidSosResponse[]` |
| `GET /bids/mechanic/{mechanicId}` | the mechanic | paging | `Page<BidSosResponse>` |
| `PATCH /bids/{bidId}/status` | the SOS owner | `{userId, status: ACCEPTED\|REJECTED}` (`CANCELLED` gives 400) | `BidSosResponse` |
| `PATCH /bids/{bidId}/cancel` | the bid's mechanic, only while PENDING | | `BidSosResponse` |

Accepting a bid moves the SOS to ACCEPTED, rejects every other pending bid, and notifies the winner and the losers. A mechanic gets 409 for a second bid on the same SOS, **including after withdrawing the first**.

### Mechanics
| Method and path | Who | Notes |
|---|---|---|
| `POST /mechanics` | mechanic | `CreateMechanicRequest`; the id is the header uuid. 201 `MechanicResponse`. **Do not call twice** (see Known gaps) |
| `GET /mechanics/{id}` | any caller | |
| `GET /mechanics/available?lat=&lng=&problemType=&radiusKm=` | customer | radius 5–20, default 15 |
| `POST /mechanics/{id}/skills`, `GET`, `PUT /{id}/skills/{skillId}`, `DELETE` | the mechanic (GET: any) | `{skillTags: [..], vehicleSpecialization}`. **Skill tags are upper-cased and must equal the Medusa service-group `key`, or the mechanic is never dispatched** |
| `PATCH /mechanics/{id}/location?lat=&lng=&sosId=` | the mechanic | 204. `sosId` is optional and is only used if this mechanic is assigned to that active SOS |
| `GET /mechanics/{id}/location` | the mechanic, or the customer on an active job with them | `{latitude, longitude, asOf}`; 403 otherwise |
| `PATCH /mechanics/{id}/availability?available=` | the mechanic | |

### Feedback and media
| Method and path | Who | Notes |
|---|---|---|
| `POST /feedback` | the SOS owner, only on a COMPLETED SOS | `{userId, mechanicId, sosId, rating 1.0–5.0, comment}`; the mechanic must be the assigned one; 409 on duplicate |
| `GET /feedback/sos/{sosId}` | participants | |
| `GET /feedback/mechanic/{mechanicId}` | any caller | paging |
| `POST /sos/{sosId}/media` | owner, assigned mechanic or eligible mechanic | multipart field `file`; **JPEG or PNG only** (HEIC is rejected), 10 MB, 8000 px; returns a 15-minute presigned URL |
| `GET /sos/{sosId}/media` | same | |

### Health
`GET /healthy`: 200, or 503 when the database is down. `GET /actuator/health`. Both are public.

### Main response shapes
- `SosRequestResponse`: `sosId, problemType, description, locationAddress, status, latitude, longitude, broadcastRadiusKm, createdAt, updatedAt, biddingExpiresAt, jobStartedAt, jobCompletedAt, durationSeconds, rebroadcastCount, user{userId,name,phone,status,refererCode}, vehicle{id,carBrand,carModel,carModelYear,fuelType}, mechanic{…}, pendingCompletionHistoryId`.
- `BidSosResponse`: `bidId, price, status, estimatedDistanceM, estimatedDurationS, estimatedDistanceText, estimatedDurationText, bidAt, respondedAt, mechanic{…}`.
- `MechanicResponse`: `mechanicId, fullName, phoneNumber, email, mechanicRole (UNDER_GARAGE|FREELANCE), referralCode, kycStatus, address, serviceRadiusKm, avgRate, rateCount, availableStatus, verificationStatus, latitude, longitude`.
- `SosStatusHistoryResponse`: `id, fromStatus, toStatus, changeAt, proposedAt, approveAt, approvedStatus (PENDING_APPROVAL|ACCEPTED|REJECTED), proposedBy, approvedBy`.
- Enums: `SosStatus` PENDING, ACCEPTED, ON_THE_WAY, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED. `BidStatus` PENDING, ACCEPTED, REJECTED, CANCELLED.

## 5. WebSocket (STOMP)

- Endpoint `/ws`. The handshake needs `user-context` or `mechanic-context` (same rules as §1); without it the upgrade is refused with 403. Identity is checked at connect only.
- Heartbeat 10 s / 10 s. App destination prefix `/app`. User queues are `/user/queue/...`.
- **Send:** `/app/mechanic/location` with `{latitude, longitude, sosId?}` (mechanic only). Errors come back on `/user/queue/errors` as `{error}`.
- **Subscribe** to per-SOS topics only for an SOS you may see (owner, bidder, or an eligible mechanic while bidding is open); wildcards are refused.

| Destination | Receiver | Payload |
|---|---|---|
| `/user/queue/sos-new-request` | eligible mechanics | `SOS_CREATED`: `type, sosId, title, body, photoUrls[≤3], estimate{remainingDistanceM, remainingDurationS, remainingDistanceText, remainingDurationText}` (recipient fields are null) |
| `/user/queue/sos-updates` (customer) | the owner | `BID_RECEIVED {type, sosId, bid}`, `BID_CANCELLED {type, sosId, bidId}`, `COMPLETION_REQUESTED {type, sosId, historyId, approveBy}` (`approveBy` is when it will be approved automatically), `COMPLETION_AUTO_APPROVED {type, sosId}`, `BIDDING_EXPIRED {type, sosId, retriesLeft, attemptsLeft}`, `SOS_AUTO_REBROADCAST {type, sosId, newRadiusKm, …}`, `SOS_REBROADCAST {type, sosId, rebroadcastCount, attemptsLeft}`; and **status frames without a `type`**: `{sosId, mechanicId, userId, fromStatus, toStatus, changedAt}` for ON_THE_WAY, ARRIVED and IN_PROGRESS |
| `/user/queue/sos-updates` (mechanic) | the winner / the losers / cancelled | winner: `{bidId, sosId, mechanicId, userId, latitude, longitude, acceptedAt}` (no `type`); losers: `BID_REJECTED {type, sosId, bidId}`; `COMPLETION_REJECTED {type, sosId, historyId}`; a status frame after the customer approves completion; **`SOS_CANCELLED {type, sosId, fromStatus}` when the customer cancels**, sent to the assigned mechanic (`fromStatus` is `ACCEPTED`, `ON_THE_WAY`, `ARRIVED` or `IN_PROGRESS`: stop driving) and to every mechanic with a pending bid (`fromStatus` is `PENDING`) |
| `/topic/sos/{sosId}/location` | subscribers of that SOS | `{sosId, mechanicId, latitude, longitude, timestamp (+07:00), remainingDistanceM, remainingDurationS, remainingDistanceText, remainingDurationText}`; the `remaining*` fields are null when there is no ETA |
| `/topic/sos/{sosId}/media` | subscribers of that SOS | `{sosId, mediaId, uploadedByRole, uploadedById, uploadedAt}` |

Location frames normally arrive through Redis pub/sub and through Kafka when Redis is unavailable. Order by `timestamp` if you must be exact.

**Not sent over WebSocket:** expiry to bidders (the owner gets `BIDDING_EXPIRED`) and a single bid reject. See Known gaps.

## 6. Push notifications (OneSignal)

Pushes target the OneSignal alias `external_id` = the user's/mechanic's UUID, so **both apps must call `OneSignal.login(<uuid>)`**. The push `data` is `{type, sosId}`. Types used: `SOS_CREATED` (not pushed, WebSocket only), `BID_RECEIVED`, `BID_ACCEPTED`, `BID_REJECTED` (one batched push for all losers), `BID_CANCELLED`, `BIDDING_EXPIRED`, `SOS_AUTO_REBROADCAST`, `COMPLETION_REQUESTED`, `COMPLETION_REJECTED`, `STATUS_ON_THE_WAY`, `STATUS_ARRIVED`, `STATUS_IN_PROGRESS`, `STATUS_COMPLETED`, `STATUS_CANCELLED` (to the customer), `SOS_CANCELLED` (to the assigned mechanic, and one batched push to mechanics with a pending bid). Delivery is best-effort: a failed push is logged and published to `sos.dlq`, never retried. A redelivered Kafka record does not send a second push (idempotency key).

## 7. Kafka topics

Partitions default to 3, replication factor 1 unless configured. Key is the `sosId` unless noted. Value is JSON.

| Topic | Key | Value |
|---|---|---|
| `sos.created` | sosId | `{sosId, userId, vehicleId, problemType, description, latitude, longitude, locationAddress, createdAt}` |
| `sos.bid.created` | sosId | `{bidId, sosId, mechanicId, price, estimatedDistanceM, estimatedDurationS, estimatedDistanceText, estimatedDurationText, bidAt}` |
| `sos.bid.accepted` | sosId | `{bidId, sosId, mechanicId, userId, latitude, longitude, acceptedAt}` |
| `sos.bid.rejected` | sosId | `{sosId, bidIds[]}` |
| `sos.bid.cancelled` | sosId | `{bidId, sosId, mechanicId, cancelledAt}` |
| `sos.status.updated` | sosId | `{sosId, mechanicId, userId, fromStatus, toStatus, changedAt}`; published for mechanic status changes, completion approval, cancel and expiry (on cancel `mechanicId` is the assigned mechanic, null when none) |
| `sos.notification` | recipient id (null for fan-out) | `{type, recipientId, recipientRole, sosId, title, body, photoUrls, estimate, recipientIds}` |
| `sos.media.uploaded` | sosId | `{sosId, mediaId, uploadedByRole, uploadedById, uploadedAt}` |
| `mechanic.location.updated` | sosId | the location event; **only used when Redis is down** |
| `sos.dlq` | original key | dead-lettered records (raw bytes for malformed input) and push failures |

**Date format on Kafka (important for consumers):** the Kafka JSON mapper has `WRITE_DATES_AS_TIMESTAMPS` on, so `LocalDateTime` fields are arrays `[2026,10,4,10,0]` and `OffsetDateTime` is epoch seconds as a decimal (`1791082800.000000000`), **not ISO strings**. This differs from REST and WebSocket. It is planned to switch to ISO strings; do not parse the current format without agreeing the change.

Events are published inside the database transaction, before commit, with no outbox: a consumer may see an event for work that later rolls back, and an event lost during a broker outage is not replayed.

## 8. Known gaps

What the other teams must know before integrating. Tracked in the vault audit and the repository issues.

1. **No user or vehicle provisioning.** `POST /sos` returns 404 "User not found" unless the user and vehicle already exist in SOS's tables. The internal team decides how that data arrives.
2. **There is no mechanic-side cancel.** A mechanic who cannot attend cannot release the job; the customer must cancel. After a customer cancel the assigned mechanic and pending bidders are notified (`SOS_CANCELLED`), but pending bids stay `PENDING` in the database until withdrawn.
3. **The completion push does not carry the `historyId`.** After a push or a missed `COMPLETION_REQUESTED` frame the app reads `pendingCompletionHistoryId` and `pendingCompletionApproveBy` from `GET /sos/{sosId}`. An unanswered request is approved automatically after the window (default 2 minutes, `SOS_COMPLETION_APPROVAL_WINDOW_SECONDS`); the product owner should confirm that is the wanted behaviour and the length.
4. **Expiry and a single bid reject send no WebSocket frame** to bidders.
5. **A mechanic cannot re-bid after withdrawing**, and rebroadcast re-offers the SOS to mechanics who already bid.
6. **A mechanic who misses the WebSocket frame never sees a new SOS:** there is no REST list of open nearby SOS.
7. **`POST /mechanics` is not idempotent.** Calling it again for an existing mechanic overwrites the row (likely resetting KYC and verification status, availability and location) and duplicates skills. Call it once.
8. **Dispatch ignores KYC and verification status, busy mechanics and location age.** Bid create does not check that the mechanic is eligible (skill, radius).
9. **Mechanic profile fields are limited:** `fullName` and `email` 30 characters, `phoneNumber` 20, with no validation message; longer values fail with 409.
10. **Plan, subscription and tag-verification endpoints do not exist,** and there is no service-to-service authentication.
11. **Run one replica:** the WebSocket broker and the bidding-expiry timers are in memory.
12. **Mechanic and driver details are visible to any authenticated caller** on `GET /mechanics/{id}` and `GET /feedback/mechanic/{id}`, and an eligible mechanic sees the customer's name and phone before bidding.
13. **Timestamp formats differ** between REST, WebSocket, location and Kafka (§2, §7); the container timezone is not pinned.
14. **Photos:** only JPEG and PNG are accepted.

## 9. Configuration the integrator needs

Required environment variables (no defaults): `SPRING_PROFILES_ACTIVE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_MIN_IDLE` (set equal to `DB_POOL_MAX`), `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT`, `GOOGLE_MAPS_API_KEY`, `ONESIGNAL_APP_ID`, `ONESIGNAL_API_KEY`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `MEDUSA_BASE_URL`, `MEDUSA_PUBLIC_KEY`. With the `prod` profile also `WEBSOCKET_ALLOWED_ORIGINS`. `S3_ENDPOINT` and `S3_BUCKET_NAME` default to a real host and bucket: set them explicitly per environment.

- **Never use the `dev` profile outside a laptop.** It enables `websocket.dev-insecure-handshake`, which lets any client connect as any user. The service refuses to start with that flag under any other profile.
- The schema is created by Hibernate; there are no migrations yet. `prod` uses `ddl-auto: validate`, so the schema and the PostGIS extension must exist before the first start.
- `SOS_COMPLETION_APPROVAL_WINDOW_SECONDS` (default 120) is how long a customer has to answer a completion request; `SOS_COMPLETION_SWEEP_INTERVAL_MS` (default 15000) is how often overdue requests are looked for.
- Medusa is called on every SOS create (timeouts 2 s connect, 3 s read; `medusa.connect-timeout-ms`, `medusa.read-timeout-ms`).
