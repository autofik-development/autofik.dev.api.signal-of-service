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
