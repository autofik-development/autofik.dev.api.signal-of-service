# Signal of Service — Project Structure

Spring Boot 4.0.6 · Java 21 · PostgreSQL/PostGIS · Kafka · WebSocket

## Package Layout

```
src/main/java/com/autofik/sos/
├── SosApplication.java
│
├── config/              Spring @Configuration beans (Kafka, WebSocket, datasource, CORS)
├── controller/          REST controllers — handle HTTP, delegate to service
├── service/             Service interfaces — define the business contract
│   └── impl/            Service implementations — concrete @Service classes
├── repository/          Spring Data JPA interfaces — database access
├── domain/
│   └── entity/          JPA @Entity classes — map to database tables
├── dto/
│   ├── request/         Incoming request bodies (validated with @Valid)
│   └── response/        Outgoing response shapes
├── exception/           Custom exceptions + @ControllerAdvice global handler
├── mapper/              Entity ↔ DTO converters
├── kafka/
│   ├── producer/        Publishes domain events to Kafka topics
│   └── consumer/        @KafkaListener classes relaying events to WebSocket
├── security/            JWT filter, UserDetailsService, SecurityFilterChain config
└── util/                Stateless helpers shared across layers
```

## Layer Responsibilities

| Layer | Role | Key Annotation |
|---|---|---|
| `controller` | Accept HTTP request, validate input, return response | `@RestController` |
| `service` | Business logic, orchestration, transaction boundary | `@Service` |
| `repository` | Database queries via Spring Data JPA | `@Repository` |
| `domain/entity` | Database table mapping | `@Entity` |
| `dto/request` | Carry and validate incoming data | — |
| `dto/response` | Shape outgoing API data | — |
| `mapper` | Convert entity ↔ DTO | — |
| `exception` | Custom error types + unified error response | `@ControllerAdvice` |
| `security` | JWT auth filter + Spring Security config | `@Configuration` |
| `config` | App-wide beans (Kafka, WebSocket, etc.) | `@Configuration` |
| `util` | Shared helpers (date, string, pagination utils) | — |

## Data Flow

```
HTTP Request
    ↓
Controller          (validates input DTO)
    ↓
Service interface   (business logic)
    ↓
Service impl        (calls repository, maps results)
    ↓
Repository          (Spring Data JPA → PostGIS/PostgreSQL)
    ↓
Entity              (JPA mapped table row)
    ↓
Mapper              (Entity → Response DTO)
    ↓
HTTP Response
```

## Infrastructure

| Service | Image | Port |
|---|---|---|
| PostgreSQL + PostGIS | `postgis/postgis:16-3.4` | `5432` |

Credentials are loaded from `.env` — see `.env.example` for required variables.

## File-by-File Reference

> Note: controllers, services, repositories, and mappers below are already implemented — this section supersedes any "not yet built" notes elsewhere.

### Root

- **`SosApplication.java`** — `@SpringBootApplication` entry point, no extra logic.

### `config/`

- **`DatabaseStartupLogger`** — `ApplicationRunner` bean that logs DB connection details (URL, username) at boot to confirm PostGIS connectivity.
- **`GoogleMapsConfig`** — builds a `GeoApiContext` bean from `google.maps.api-key` (2s connect / 5s read timeout); empty key → no-op context, fails gracefully.
- **`KafkaConfig`** — defines 8 topics (`sos.created`, `sos.bid.created/accepted/rejected`, `sos.status.updated`, `sos.dlq`, `mechanic.location.updated`, `sos.notification`), each with 3 partitions / replication factor 1. Producer: JSON values, `acks=all`, idempotence + 3 retries. Consumer: `JsonDeserializer`, trusted package `com.autofik.sos.*`, earliest offset reset.
- **`SecurityConfig`** — `SessionCreationPolicy.STATELESS`, CSRF disabled, all endpoints permitted (JWT filter pending SSO team's token format).
- **`WebConfig`** — CORS for `/api/**`: all origins/methods, exposes `Authorization` header, max-age 3600s.
- **`WebSocketConfig`** — STOMP endpoint `/ws`, app prefix `/app`, brokers `/topic` (broadcast) + `/queue` (user-specific); all origin patterns allowed.

### `domain/entity/`

- **`SosRequest`** — core entity: `problemType`, `description`, `status` (`SosStatus`, default `PENDING`), PostGIS `location` (SRID 4326) + reverse-geocoded `locationAddress`, `createdAt`/`updatedAt`. Relations: `User` + `Vehicle` (required, many-to-one), `BidSos[]`, `SosStatusHistory[]`, `Feedback` (1:1, cascade all).
- **`BidSos`** — mechanic's offer: `price` (BigDecimal 10,2), `note`, `status` (`BidStatus`, default `PENDING`), `bidAt`/`respondedAt`, plus Google-Maps-derived `estimatedDistanceM`/`estimatedDurationS`/`estimatedDistanceText`/`estimatedDurationText`. Relations: `SosRequest` + `Mechanic` (both required).
- **`SosStatusHistory`** — append-only audit log of status transitions; propose/approve workflow via `proposedBy` (Mechanic, required) and `approvedBy` (User, optional), with `proposedAt`/`approveAt`/`approvedStatus`.
- **`Feedback`** — 1:1 with `SosRequest` (unique), `rating` (BigDecimal 2,1, `@Check` 1.0–5.0), `comment`, `createdAt`; required `User` + `Mechanic`.
- **`User`** — `userId` (PK, renamed from `id` for naming consistency with other entities — e.g. `mechanicId`, `sosId`), `name` (required), unique optional `phone`/`email`, `hashPassword` (**nullable on this branch** — was NOT NULL), audit timestamps. `@Check` enforces at least one of phone/email. Relations: `Vehicle[]` (cascade all), `SosRequest[]`, `Feedback[]`, `approvedHistories`.
- **`Mechanic`** — `isAvailable` (default true), `ratingAvg` (3,2, default 0.00), `baseLocation`/`currentLocation` (PostGIS Points), `currentH3Index` (B-tree indexed, length 16), `serviceRadiusM` (default 10km), `hashPassword` (**nullable on this branch**). Relations: `MechanicSpecialization[]` (cascade all), `Feedback[]`, `BidSos[]`, `proposedHistories`.
- **`Vehicle`** — `plateNumber` (unique, required), `brand`/`model`/`year`/`type`/`fuelType`/`color`, `isDefault`; many-to-one required `User`.
- **`MechanicSpecialization`** / **`MechanicSpecializationId`** — join entity (composite key: mechanic UUID + specialization UUID) carrying `yearOfExperience` + `experienceLevel`.
- **`Specialization`** — master list of repair specialties (unique `name`).

### `domain/enums/`

- **`SosStatus`** — `PENDING → ACCEPTED → ON_THE_WAY → ARRIVED → COMPLETED`, or `CANCELLED` from any non-terminal state.
- **`BidStatus`** — `PENDING | ACCEPTED | REJECTED`.
- **`ApprovedStatus`** — `PENDING_APPROVAL | ACCEPTED | REJECTED` (drives the `SosStatusHistory` approval workflow).
- **`ExperienceLevel`** — `JUNIOR | MID | SENIOR`.

### `dto/`

- **`external/DistanceInfo`** — record wrapping a Google Distance Matrix result (`distanceMeters`, `durationSeconds`, `distanceText`, `durationText`); produced by `GoogleMapsServiceImpl`, consumed in `BidServiceImpl.create()`.
- **`event/`** — Kafka event records published by `SosEventProducer` / consumed by `MechanicLocationConsumer`: `SosCreatedEvent`, `BidCreatedEvent`, `BidAcceptedEvent`, `BidRejectedEvent` (batch — `sosId` + `List<UUID> bidIds`), `SosStatusUpdatedEvent`, `MechanicLocationEvent` (consumed, not produced, by this service), `SosNotificationEvent` (generic `{type, recipientId, recipientRole, sosId, title, body}` fanned out to the external notification-service).
- **`request/`** — `CreateSosRequest` (lat/lng reverse-geocoded), `CreateUserRequest` (`AtLeastOneContact`-validated), `CreateMechanicRequest` (lat/lng → H3 index), `CreateBidRequest` (`@DecimalMin("0.01")` price), `CreateVehicleRequest`, `CreateFeedbackRequest` (`1.0`–`5.0` rating), `UpdateBidStatusRequest`, `ProposeStatusChangeRequest`, `ApproveStatusChangeRequest`.
- **`response/`** — `SosRequestResponse` (lat/lng extracted from JTS `Point` by `SosMapper`; nests `UserResponse`/`VehicleResponse`, `mechanic` ignored), `UserResponse`, `VehicleResponse`, `MechanicResponse` (location fields intentionally **not** exposed), `BidSosResponse` (nests `MechanicResponse`), `FeedbackResponse`, `SosStatusHistoryResponse` (nests both `MechanicResponse` and `UserResponse`), `MechanicSpecializationResponse`, `SpecializationResponse`.

### `exception/`

- **`ErrorResponse`** — record `{status, error, message, timestamp}`.
- **`GlobalExceptionHandler`** — `@RestControllerAdvice`: `EntityNotFoundException`→404, `IllegalStateException`→409, `MethodArgumentNotValidException`→400 (field-error map), any other `Exception`→500; all 404/409/500 handlers populate the full `ErrorResponse` (status code + reason phrase + message + timestamp).

### `mapper/` (MapStruct, compile-time)

`SosMapper` (uses `UserMapper`/`VehicleMapper`; extracts lat/lng from JTS `Point`), `UserMapper` (direct — `User.userId` now matches `UserResponse.userId` by name, no explicit `@Mapping` needed), `VehicleMapper` (direct), `MechanicMapper` (location fields implicitly unmapped/hidden), `BidSosMapper` (uses `MechanicMapper`), `FeedbackMapper` (uses `UserMapper`/`MechanicMapper`), `SosStatusHistoryMapper` (uses `MechanicMapper`/`UserMapper`), `SpecializationMapper` (direct), `MechanicSpecializationMapper` (uses `SpecializationMapper`).

### `kafka/`

- **`producer/SosEventProducer`** — single `@Component` wrapping `KafkaTemplate<String, Object>`; one `publishX()` method per event type, keyed by `sosId` (or `recipientId` for notifications) so events for the same SOS land on the same partition in order.
- **`consumer/MechanicLocationConsumer`** — `@KafkaListener` on `mechanic.location.updated` (group id from `spring.kafka.consumer.group-id`); relays each `MechanicLocationEvent` to `/topic/sos/{sosId}/location` via `SimpMessagingTemplate`.

### `repository/` (Spring Data JPA)

Standard per-entity finders (`findByUserId`, `findByEmail`, etc.) plus:
- **`BidSosRepository`** — `existsBySosRequestSosIdAndMechanicMechanicId()` prevents duplicate bids.
- **`MechanicRepository`** — two-stage geo lookup: `findByH3Cells(List<String>)` (JPQL, B-tree coarse filter) → `filterByRadius(List<UUID>, lat, lng)` (native `ST_DWithin` exact PostGIS check).
- **`SosStatusHistoryRepository`** — `findBySosRequestSosIdOrderByChangeAtAsc()` for chronological audit trail.

### `service/` + `service/impl/`

- **`SosServiceImpl`** — `create()` builds a JTS `Point` from lat/lng and reverse-geocodes it via Google Maps for `locationAddress`, then publishes `SosCreatedEvent` + a `SOS_CREATED` notification (fan-out, `recipientId=null` — matching is the notification-service's job); `proposeStatusChange()`/`approveStatusChange()` implement the two-phase workflow against an immutable `VALID_TRANSITIONS` map — on `ACCEPTED` approval, publishes `SosStatusUpdatedEvent`, pushes it to the proposing mechanic's `/queue/sos-updates`, and fires the matching lifecycle notification (`ON_THE_WAY`/`ARRIVED`/`COMPLETED`); `cancel()` allows cancellation from any non-terminal state and publishes `SosStatusUpdatedEvent(CANCELLED)` + a `STATUS_CANCELLED` notification to the user (`mechanicId` is `null` — resolving the assigned mechanic via `BidSosRepository` is deferred).
- **`BidServiceImpl`** — `create()` validates the `SosRequest` is `PENDING`, mechanic is available, no duplicate bid exists, then computes distance/duration via `GoogleMapsService.getDistanceInfo()` and publishes `BidCreatedEvent` + a `BID_RECEIVED` notification to the SOS owner; `updateStatus()` to `ACCEPTED` auto-rejects all other pending bids (now returns the rejected list), flips `SosRequest.status` to `ACCEPTED`, publishes `BidAcceptedEvent` (+ WS push to the winning mechanic) and `BidRejectedEvent`, and fires `BID_ACCEPTED`/`BID_REJECTED` notifications to each mechanic involved.
- **`MechanicServiceImpl`** — `create()` computes an H3 index (resolution 7) for base/current location; `findAvailable()` runs a 3-stage funnel: H3 `kRing` (radius 4) coarse filter → PostGIS `ST_DWithin` exact radius check → in-memory specialization filter; `updateLocation()` updates `currentLocation`/`currentH3Index` for real-time GPS tracking.
- **`GoogleMapsServiceImpl`** — `reverseGeocode()` and `getDistanceInfo()` both fail-soft (return `null`, log a warning) on API error so core flows never block on a Maps outage.
- **`UserServiceImpl`** — basic CRUD; enforces at-least-one-contact at the service layer too.
- **`FeedbackServiceImpl`** — `create()` requires `SosRequest.status == COMPLETED`, then `recalculateMechanicRating()` averages all of a mechanic's feedback ratings (HALF_UP, precision 2,1) and updates `Mechanic.ratingAvg`.

### `controller/`

| Controller | Base path | Endpoints |
|---|---|---|
| `SosController` | `/api/sos` | `POST /`, `GET /{sosId}`, `GET /user/{userId}`, `GET /status/{status}`, `POST /propose-status`, `POST /approve-status`, `PATCH /{sosId}/cancel?userId=` |
| `BidController` | `/api/bids` | `POST /`, `GET /sos/{sosId}`, `GET /mechanic/{mechanicId}`, `PATCH /{bidId}/status` |
| `BidSocketController` | STOMP `/app/bids*` | `/app/bids`, `/app/bids/decision`, `/app/bids/cancel`, `/app/bids/sync` — same `BidService` calls as `BidController`, replies on `/user/queue/bid-results`, errors on `/user/queue/errors` |
| `MechanicLocationSocketController` | STOMP `/app/mechanic/location` | GPS ping, socket alternative to the REST location PATCH |
| `UserController` | `/api/users` | `POST /`, `GET /{userId}`, `GET /email?email=` |
| `MechanicController` | `/api/mechanics` | `POST /`, `GET /{mechanicId}`, `GET /available?lat=&lng=&problemType=`, `PATCH /{mechanicId}/location?lat=&lng=` |
| `FeedbackController` | `/api/feedback` | `POST /`, `GET /sos/{sosId}`, `GET /mechanic/{mechanicId}` |

### `validation/`

- **`AtLeastOneContact`** + **`AtLeastOneContactValidator`** — class-level constraint on `CreateUserRequest` requiring phone or email.

## Architectural Notes

- **State machine**: `SosStatus` only advances linearly (`PENDING→ACCEPTED→ON_THE_WAY→ARRIVED→COMPLETED`); `CANCELLED` is reachable from any non-terminal state. Enforced by `SosServiceImpl.VALID_TRANSITIONS`.
- **Two-phase status changes**: a mechanic proposes a transition, the user approves/rejects it; every proposal and decision is recorded in `SosStatusHistory`.
- **Bid acceptance cascade**: accepting one bid auto-rejects all other pending bids on the same `SosRequest` and advances it to `ACCEPTED`.
- **Mechanic discovery funnel**: H3 grid (coarse) → PostGIS `ST_DWithin` (exact radius) → in-memory specialization match.
- **Google Maps integration**: reverse geocoding for request addresses, Distance Matrix for bid ETA/distance — both degrade to `null` rather than failing the request on API error.
- **In-flight branch change** (`15-feature-add-google-maps-integration-and-location-description`): `User.hashPassword` and `Mechanic.hashPassword` were changed from `NOT NULL` to nullable, likely to support SSO-only accounts alongside location features.
- **Kafka event layer**: every SOS/bid lifecycle transition publishes a domain event (`sos.created`, `sos.bid.created/accepted/rejected`, `sos.status.updated`) and a fan-out notification (`sos.notification`) via `SosEventProducer`. Mechanic GPS pings arrive on `mechanic.location.updated` and are relayed to `/topic/sos/{sosId}/location` by `MechanicLocationConsumer`. Two items are deliberately deferred pending the SSO team: (1) `cancel()` publishes with `mechanicId=null` rather than resolving the assigned mechanic via `BidSosRepository`; (2) `/user/{mechanicId}/queue/sos-updates` pushes are wired in code but won't deliver until WebSocket sessions carry a real `Principal`.
