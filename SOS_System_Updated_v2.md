# ROADSIDE ASSISTANCE (SOS) SYSTEM
## Full System Documentation
### ERD Documentation | System Requirements Specification
**Version 1.0 | May 2026**
*Confidential – Internal Use Only*

## 1. System Overview
This document provides full documentation for the Roadside Assistance (SOS) System, combining Entity-Relationship Diagram (ERD) documentation and system requirements.
The system connects vehicle owners who require emergency roadside assistance with available mechanics.
It manages the full lifecycle of an SOS request — from creation and mechanic assignment, through status tracking and resolution, to user feedback.

### 1.1 Core Actors
| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| location | GEOGRAPHY(Point, 4326) | NOT NULL | GPS point of breakdown location. Stores both latitude and longitude as a single WGS-84 geometry (GEOGRAPHY Point, SRID 4326). |
| create_at | TIMESTAMP | DEFAULT NOW() | Timestamp when the request was created |
| update_at | TIMESTAMP | — | Timestamp of the last status update |
| user_id | INT / UUID | FK → user | User who submitted the request |
| vehicle_id | INT / UUID | FK → vehicle | Vehicle involved in the incident |
| Mechanic_id | INT / UUID | FK → Mechanic | Mechanic assigned to the request |

## 2. Entity-Relationship Diagram (ERD)
This section documents all entities, their attributes, data types, and constraints in the SOS System database schema.

### 2.1 Key Legend
| Marker | Meaning |
| :--- | :--- |
| PK | Primary Key – uniquely identifies each row |
| FK | Foreign Key – references the PK of another table |
| PK, FK | Acts as both Primary Key and Foreign Key |
| — | Regular column (no key constraint) |

### 2.2 Entities & Attributes

#### 2.2.1 Entity: user
Represents registered users of the platform. A user can own one or more vehicles and submit SOS requests for roadside assistance.
Users can also provide feedback on completed service requests. Authentication and identity are managed by the SSO team;
this entity stores only profile data.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| user_id | INT / UUID | PK | Unique identifier for each user |
| name | VARCHAR | NOT NULL | Full name of the user |
| phone | VARCHAR | NOT NULL | Contact phone number |
| email | VARCHAR | UNIQUE, NOT NULL | Email address (identity managed by SSO) |

#### 2.2.2 Entity: vehicle
Stores vehicle information owned by users. A user may register multiple vehicles;
one can be designated as the default for quick SOS submission.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| vehicle_id | INT / UUID | PK | Unique identifier for each vehicle |
| user_id | INT / UUID | FK → user | Owner of this vehicle |
| plate_number | VARCHAR | UNIQUE, NOT NULL | License plate number |
| brand | VARCHAR | NOT NULL | Vehicle manufacturer/brand |
| model | VARCHAR | NOT NULL | Vehicle model name |
| year | YEAR / INT | NOT NULL | Manufacturing year |
| type | VARCHAR | — | Vehicle type (sedan, SUV, truck, etc.) |
| fuel_type | VARCHAR | — | Fuel type (petrol, diesel, electric, etc.) |
| color | VARCHAR | — | Vehicle color |
| is_default | BOOLEAN | DEFAULT FALSE | Whether this is the user’s primary vehicle |

#### 2.2.3 Entity: Sos_request
The central entity of the system. Captures all details of a user’s emergency request, including the problem type, location, assigned mechanic, and current status.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| sos_id | INT / UUID | PK | Unique identifier for each SOS request |
| problem_type | VARCHAR | NOT NULL | Category of problem (flat tyre, engine failure, etc.) |
| description | TEXT | — | Detailed description of the issue |
| status | ENUM | NOT NULL | Current status (PENDING, ACCEPTED, ON_THE_WAY, ARRIVED, COMPLETED, CANCELLED) |
| location | GEOGRAPHY(Point, 4326) | NOT NULL | GPS point of breakdown location. Stores both latitude and longitude as a single WGS-84 geometry. |
| created_at | TIMESTAMP | DEFAULT NOW() | Timestamp when the request was created |
| updated_at | TIMESTAMP | — | Timestamp of the last status update |
| user_id | INT / UUID | FK → user | User who submitted the request |
| vehicle_id | INT / UUID | FK → vehicle | Vehicle involved in the incident |
| mechanic_id | INT / UUID | FK → Mechanic | Mechanic assigned to the request (set when a bid is accepted) |

#### 2.2.4 Entity: Mechanic
Represents service providers (mechanics) who can respond to SOS requests.
Mechanics have availability flags and average ratings calculated from user feedback.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| Mechanic_id | INT / UUID | PK | Unique identifier for each mechanic |
| name | VARCHAR | NOT NULL | Full name of the mechanic |
| Phone | VARCHAR | NOT NULL | Contact phone number |
| email | VARCHAR | UNIQUE, NOT NULL | Email address |
| is_available | BOOLEAN | DEFAULT TRUE | Whether the mechanic is currently available |
| rating_avg | DECIMAL | DEFAULT 0 | Average rating derived from feedback scores |

#### 2.2.5 Entity: bid_sos
Bidding/offer layer that sits between Sos_request and Mechanic. When a new SOS is broadcast, each interested mechanic submits a bid.
The system (or the user) then selects one bid to accept, at which point the SOS is formally assigned.
This decouples “interest from mechanics” from “assignment” and prevents double-assignment race conditions.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| bid_id | INT / UUID | PK | Unique identifier for each bid |
| Sos_id | INT / UUID | FK → Sos_request | The SOS request this bid relates to |
| Mechanic_id | INT / UUID | FK → Mechanic | Mechanic who submitted the bid |
| bid_status | ENUM | NOT NULL | Bid state: PENDING, ACCEPTED, REJECTED, EXPIRED |
| estimated_price | DECIMAL | — | Mechanic’s quoted price for the job (optional) |
| eta_minutes | INT | — | Estimated arrival time in minutes provided by the mechanic |
| note | TEXT | — | Optional message from mechanic to user about the bid |
| created_at | TIMESTAMP | DEFAULT NOW() | Timestamp when the bid was submitted |

#### 2.2.6 Entity: Mechanic_specialization
Junction (bridge) entity linking mechanics to their specializations. Holds additional attributes such as years of experience and proficiency level for each mechanic-specialization pairing.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| Mechanic_id | INT / UUID | PK, FK → Mechanic | Mechanic reference (composite PK) |
| spca_id | INT / UUID | PK, FK → specialization | Specialization reference (composite PK) |
| Year_of_experience | INT | — | Number of years the mechanic has in this specialization |
| experience_level | ENUM / VARCHAR | — | Proficiency tier (junior, mid, senior) |

#### 2.2.7 Entity: specialization
A lookup/reference table enumerating the types of mechanical specializations available (e.g. engine repair, electrical, tyres, AC, etc.).

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| spca_id | INT / UUID | PK | Unique identifier for each specialization |
| name | VARCHAR | UNIQUE, NOT NULL | Name of the specialization |

#### 2.2.8 Entity: Sos_status_history
Audit trail table that records every status transition of an SOS request.
Captures who proposed the change and who approved it, along with timestamps.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| id | INT / UUID | PK | Unique identifier for each history record |
| change_at | TIMESTAMP | NOT NULL | Timestamp when the status change occurred |
| from_status | ENUM / VARCHAR | NOT NULL | Previous status value before the change |
| to_status | ENUM / VARCHAR | NOT NULL | New status value after the change |
| Proposed_at | TIMESTAMP | — | Timestamp when the change was proposed |
| approve_at | TIMESTAMP | — | Timestamp when the change was approved |
| Approved_status | ENUM | — | Mark as user accept the process or not |
| proposed_by | INT / UUID | FK → user/Mechanic | Actor who proposed the status change |
| approved_by | INT / UUID | FK → user/Mechanic | Actor who approved the status change |
| Sos_id | INT / UUID | FK → Sos_request | The SOS request this history entry belongs to |

#### 2.2.9 Entity: Feedback
Stores user reviews submitted after an SOS request is completed.
Each feedback entry links to the specific request, the user who submitted it, and the mechanic who was reviewed.

| Column Name | Data Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| ID | INT / UUID | PK | Unique identifier for each feedback record |
| Comment | TEXT | — | Free-text review written by the user |
| rating | INT / DECIMAL | CHECK 1–5 | Numeric rating score (1 = worst, 5 = best) |
| create_at | TIMESTAMP | DEFAULT NOW() | Timestamp when the feedback was submitted |
| user_id | INT / UUID | FK → user | User who gave the feedback |
| Sos_id | INT / UUID | FK → Sos_request | The SOS request being reviewed |
| Mechanic_id | INT / UUID | FK → Mechanic | Mechanic being reviewed |

### 2.3 Entity Relationships
The table below summarises all relationships between entities, their cardinality, the foreign key column involved, and a plain-English description.

| From Entity | Relationship | To Entity | Via FK | Description |
| :--- | :--- | :--- | :--- | :--- |
| user | One → Many | vehicle | vehicle.user_id | A user owns one or more vehicles |
| user | One → Many | Sos_request | Sos_request.user_id | A user creates one or more SOS requests |
| vehicle | One → Many | Sos_request | Sos_request.vehicle_id | A vehicle is referenced in one or more SOS requests |
| Mechanic | One → Many | Sos_request | Sos_request.Mechanic_id | A mechanic responds to one or more SOS requests |
| Sos_request | One → Many | bid_sos | bid_sos.Sos_id | An SOS request receives one or more mechanic bids |
| Mechanic | One → Many | bid_sos | bid_sos.Mechanic_id | A mechanic submits one or more bids on SOS requests |
| Sos_request | One → Many | Sos_status_history | Sos_status_history.Sos_id | Each SOS request has many status history entries |
| user | One → Many | Sos_status_history |  approved_by | A user approves status changes |
| Mechanic | One → Many | Sos_status_history | proposed_by  | A Mechanic proposes  status changes |
| Mechanic | Many → Many | specialization | Mechanic_specialization | A mechanic has one or more specializations; a specialization belongs to many mechanics |
| user | One → Many | Feedback | Feedback.user_id | A user gives one or more feedback entries |
| Sos_request | One → 0/1 | Feedback | Feedback.Sos_id | An SOS request may have zero or one feedback entry |
| Mechanic | One → Many | Feedback | Feedback.Mechanic_id | A mechanic is referenced in one or more feedback entries |

### 2.4 Design Notes & Assumptions
| Design Decision | Details |
| :--- | :--- |
| Status Lifecycle | Sos_status_history provides a full audit trail; the current status is denormalized into Sos_request.Status for fast querying. |
| Composite PK | In Mechanic_specialization, the combination (Mechanic_id, spca_id) forms the PK, ensuring a mechanic cannot have the same specialization twice. |
| Feedback Cardinality | The diagram shows a zero-or-one relationship from Sos_request to Feedback, meaning feedback is optional and submitted once per request. |
| Polymorphic FK | proposed_by / approved_by in Sos_status_history may reference either a user or a mechanic; implementation may use a shared actor table or separate FK columns. |
| rating_avg Sync | This should be computed/denormalized from the Feedback table. A trigger or scheduled job should keep it in sync. |
| is_available Flag | Should be updated automatically when a mechanic accepts or completes a request to ensure accurate availability filtering. |
| is_default Vehicle | Enforcing only one default vehicle per user requires an application-level check or a partial unique index. |
| bid_sos Bidding Layer | bid_sos decouples mechanic interest from formal assignment. Multiple mechanics can bid on the same SOS; only the accepted bid triggers assignment. Accepted bid should set Sos_request.Mechanic_id and mark the SOS as ACCEPTED; all other bids for that request should be set to REJECTED or EXPIRED atomically (use a DB transaction or optimistic locking to prevent race conditions). |

## 3. System Requirements Specification
This section defines both the functional and non-functional requirements for the SOS system backend.
Requirements are divided into must-have (MVP) and nice-to-have categories.

### 3.1 Must-Have Requirements (Backend)
These are the core requirements your system must implement for the SOS flow to work at all.

#### 3.1.1 Functional Requirements

**Authentication & Identity (Handled by SSO Team)**
* ℹ User and mechanic registration, login, and session/token management are fully delegated to the SSO team. The SOS backend does not implement these flows.
* ℹ The SOS backend expects a valid SSO-issued token (e.g., JWT Bearer) on protected endpoints. Identity claims (user_id, role) are extracted from the token; no separate login API is provided by this service.

**Mechanic Profile & Location**
* Backend must store mechanic info: name, contact, skills, service radius, and current/base location.
* System must have a way to mark mechanics as available or unavailable.

**Create SOS Request**
* Backend must expose POST `/api/sos` receiving user ID (from auth), current GPS location, and problem type.
* Creates an SOS record with a status field (e.g., PENDING, ACCEPTED, CANCELLED, COMPLETED).
* SOS record must store timestamp and location for tracking and later reporting.

**Find Nearby Mechanics**
* When an SOS is created, backend must find mechanics within a certain radius of the user's location.
* Basic version: filter mechanics by city/area or simple distance calculation using coordinates.

**Broadcast SOS to Nearby Mechanics**
* Backend must send 'new SOS' notifications to candidate mechanics.
* Published as a Kafka event to topic `sos.created`. Downstream consumers (e.g. a notification service) fan the event out to nearby mechanics via FCM/APNS push or a WebSocket gateway.
* Kafka event payload includes: `sos_id`, location (GeoJSON Point), `problem_type`, `created_at`. Consumers filter mechanics by proximity.

**Mechanic Accepts an SOS**
* Backend must allow mechanics to accept an SOS with POST `/api/sos/{id}/accept`.
* Once one mechanic accepts, backend must mark the SOS as ACCEPTED and link it to that mechanic.
* Further accepts must be rejected or marked as 'too late' to prevent double-assignment.

**Status Tracking**
* Mechanic updates status: ON_THE_WAY, ARRIVED, COMPLETED.
* User can query SOS status via GET `/api/sos/{id}`.
* System should maintain an action log for all status changes.

**Basic Admin Management**
* Backend must allow listing all SOS requests and mechanics for monitoring.
* Even a simple admin API is sufficient for debugging and project evaluation.

#### 3.1.2 Non-Functional Requirements (Must-Have)
| NFR | Requirement |
| :--- | :--- |
| Availability & Reliability | API should not crash; handle invalid input gracefully. |
| Security Basics | SSO-authenticated users/mechanics only; the SOS service validates Bearer tokens issued by the SSO provider. No public anonymous SOS acceptance. |
| Data Integrity | An SOS cannot be in conflicting states (e.g., accepted by multiple mechanics). |

### 3.2 Nice-to-Have Requirements
These features make the system more polished and production-ready but are not strictly required for the MVP.

#### 3.2.1 Functional Nice-to-Haves

**Real-Time Updates via Kafka + WebSocket Gateway**
* Kafka events drive real-time updates. A WebSocket gateway subscribes to relevant topics and pushes changes to connected clients (user and mechanic apps).
* Example: user sees mechanic status change to 'On the way' instantly.

**Mechanic Rating & Feedback**
* After completion, user can rate the mechanic and leave a comment.
* Backend stores rating and can compute average score per mechanic.

**Automatic Price Estimation**
* Based on issue type (battery, tire, towing) and distance, backend suggests estimated cost.
* Can be rule-based for the initial version.

**Multiple Service Types & Filters**
* User can specify detailed issue categories.
* Backend can match with mechanics only if they support that service (e.g., towing vs tire vs engine).

**History & Reports**
* Users: list past SOS requests and statuses.
* Mechanics: list completed jobs and revenue estimate.
* Admin: statistics like number of breakdowns per day/area.

**GPS Route Links**
* Backend sends or stores a map URL (e.g., Google Maps link) from mechanic to user location.
* Simple to generate on backend using stored coordinates.

**Fallback Manual Dispatch**
* Admin can manually assign a mechanic to an SOS via an admin API if no one accepts automatically.

**Offline / Weak Signal Support**
* Queue SOS until connection returns, or integrate with SMS if app can send SMS to backend gateway.
* Some advanced systems use satellite or alternative channels (long-term roadmap).

#### 3.2.2 Non-Functional Nice-to-Haves
| NFR | Requirement |
| :--- | :--- |
| Scalability | Design endpoints and DB schema for easy scaling: pagination, indexes on key columns. |
| Logging & Monitoring | Record events for debugging and analysis. |
| Role-Based Access Control | Separate roles for user, mechanic, and admin with appropriate permissions. |

## 4. API Endpoints Summary
The table below summarises the key backend API endpoints required by the system.

| Method | Endpoint | Actor | Description |
| :--- | :--- | :--- | :--- |
| POST | /api/sos | User | Create SOS request. Auth: Bearer token. Body: { vehicle_id, problem_type, description?, location: WKT Point or GeoJSON }. Returns 201 { sos_id, status: "PENDING", created_at }. Publishes sos.created Kafka event; downstream notification service fans out to nearby mechanics. |
| GET | /api/sos/{id} | User / Mechanic | Get SOS details. Auth: Bearer token (owner user or assigned mechanic). Returns full SOS object including status, location, assigned mechanic (if any), and active bids summary. |
| POST | /api/sos/{id}/accept | Mechanic | [Deprecated — use bid flow] Direct accept endpoint kept for backward compatibility. Mechanic accepts open SOS atomically; marks SOS as ACCEPTED and assigns mechanic. Returns 409 if another mechanic already accepted. |
| POST | /api/sos/{id}/bids | Mechanic | Submit bid on a PENDING SOS. Auth: Mechanic Bearer token. Body: { estimated_price?, eta_minutes, note? }. Validates SOS is PENDING and mechanic has not already bid. Returns 201 { bid_id, bid_status: "PENDING" }. |
| GET | /api/sos/{id}/bids | User | List all bids for a PENDING SOS. Auth: Owner user Bearer token. Returns array of bids: [ { bid_id, mechanic: { id, name, rating_avg }, estimated_price, eta_minutes, note, bid_status, created_at } ]. |
| PATCH | /api/sos/{id}/bids/{bid_id}/accept | User | Accept a mechanic bid. Auth: Owner user Bearer token. Body: (none). Atomically: sets bid_status=ACCEPTED, sets all other bids for this SOS to REJECTED, sets sos.mechanic_id and sos.status=ACCEPTED. Returns 200 { sos_id, mechanic_id, status: "ACCEPTED" }. Returns 409 if SOS already accepted. |
| PATCH | /api/sos/{id}/status | Mechanic | Update SOS status. Auth: Assigned mechanic Bearer token. Body: { status: "ON_THE_WAY" \| "ARRIVED" \| "COMPLETED" }. Validates caller is the assigned mechanic. Appends row to sos_status_history. Returns 200 { sos_id, status }. |
| POST | /api/sos/{id}/feedback | User | Submit rating and comment after service completion |
| GET | /api/mechanics | Admin | List all registered mechanics and availability |
| GET | /api/admin/sos | Admin | List all SOS requests for monitoring |
| POST | /api/admin/sos/{id}/assign | Admin | Manually assign a mechanic to an SOS (fallback) |

### 4.1 Detailed API Design
This section provides full request/response contracts for the three core flows: creating an SOS request, submitting a mechanic bid, and the mechanic accept/reject bid flow.
All endpoints require a valid SSO-issued Bearer token; the user_id or mechanic_id is extracted from the token and must not be sent in the request body.

#### 4.1.1 Create SOS Request — POST /api/sos
| Property | Detail |
| :--- | :--- |
| Method & URL | POST /api/sos |
| Auth | Bearer token (SSO-issued JWT). Role: User. user_id extracted from token claims. |
| Request Body | { vehicle_id: UUID (required), problem_type: string (required), description: string (optional), location: GeoJSON Point (required) — e.g. { "type": "Point", "coordinates": [lng, lng] } or WKT string e.g. "POINT(103.8198 1.3521)". Stored as GEOGRAPHY(Point, 4326). } |
| Success Response | 201 Created — { sos_id: UUID, status: "PENDING", user_id: UUID, vehicle_id: UUID, problem_type: string, location: GeoJSON Point, created_at: ISO timestamp } |
| Error Responses | 400 Bad Request (missing/invalid fields) \| 401 Unauthorized (invalid/missing token) \| 403 Forbidden (vehicle_id does not belong to user) \| 404 Not Found (vehicle_id not found) |
| Side Effects | Inserts row in sos_status_history (from_status: null, to_status: PENDING). Publishes Kafka event to topic sos.created with payload: { event_type: "SOS_CREATED", sos_id, user_id, vehicle_id, problem_type, description, location: GeoJSON Point, created_at }. Partition key: sos_id. A Notification Consumer subscribes, queries nearby available mechanics, and delivers per-mechanic push (FCM/APNS) or WebSocket frame. |

#### 4.1.2 Mechanic Submit Bid — POST /api/sos/{id}/bids
| Property | Detail |
| :--- | :--- |
| Auth | Bearer token (SSO-issued JWT). Role: Mechanic. mechanic_id extracted from token claims. |
| Path Param | {id} = sos_id (UUID). SOS must exist and have status PENDING. |
| Request Body | { eta_minutes: integer (required, > 0), estimated_price: decimal (optional), note: string (optional, max 500 chars) } |
| Validations | SOS status must be PENDING. Mechanic must not have an existing PENDING bid on this SOS (unique constraint on sos_id + mechanic_id where bid_status = PENDING). Mechanic must be is_available = true. |
| Success Response | 201 Created — { bid_id: UUID, sos_id: UUID, mechanic_id: UUID, bid_status: "PENDING", eta_minutes: integer, estimated_price: decimal\|null, note: string\|null, created_at: ISO timestamp } |
| Error Responses | 400 Bad Request (missing eta_minutes or invalid values) \| 401 Unauthorized \| 403 Forbidden (caller is not a mechanic or is_available=false) \| 404 Not Found (sos_id not found) \| 409 Conflict (SOS is no longer PENDING, or mechanic already has a bid on this SOS) |
| Side Effects | Inserts row in bid_sos with bid_status=PENDING. Publishes Kafka event to topic sos.bid.created with payload: { event_type: "BID_SUBMITTED", bid_id, sos_id, mechanic_id, eta_minutes, estimated_price, note, created_at }. Partition key: sos_id. A Notification Consumer delivers a push/WebSocket notification to the SOS owner: "A mechanic has submitted a bid." |

#### 4.1.3 User Accepts a Mechanic Bid (Mechanic Accept/Reject Flow) — PATCH /api/sos/{id}/bids/{bid_id}/accept
This endpoint is the core of the bid acceptance flow.
When a user accepts one mechanic’s bid, the system must atomically assign that mechanic and reject all competing bids to prevent double-assignment race conditions.

| Property | Detail |
| :--- | :--- |
| Auth | Bearer token (SSO-issued JWT). Role: User. Caller must be the user who owns the SOS request (sos.user_id == token.user_id). |
| Request Body | None (all info carried by path params) |
| Atomic DB Transaction | Step 1: SELECT sos WHERE sos_id=:id AND status=’PENDING’ FOR UPDATE (row-lock). Step 2: If not found or status != PENDING, rollback and return 409. Step 3: UPDATE bid_sos SET bid_status=’ACCEPTED’ WHERE bid_id=:bid_id AND sos_id=:id. Step 4: UPDATE bid_sos SET bid_status=’REJECTED’ WHERE sos_id=:id AND bid_id != :bid_id AND bid_status=’PENDING’. Step 5: UPDATE sos_request SET mechanic_id=<bid.mechanic_id>, status=’ACCEPTED’, updated_at=NOW() WHERE sos_id=:id. Step 6: INSERT sos_status_history (from_status: PENDING, to_status: ACCEPTED). Step 7: COMMIT. |
| Success Response | 200 OK — { sos_id: UUID, status: "ACCEPTED", mechanic_id: UUID, accepted_bid: { bid_id, eta_minutes, estimated_price } } |
| Error Responses | 401 Unauthorized \| 403 Forbidden (caller is not SOS owner) \| 404 Not Found (sos_id or bid_id not found, or bid does not belong to this SOS) \| 409 Conflict (SOS status is not PENDING — already accepted or cancelled) |
| Post-Accept Notifications | After DB transaction commits: (1) Publish to topic sos.bid.accepted — payload: { event_type: "BID_ACCEPTED", sos_id, bid_id, mechanic_id, user_id, location: GeoJSON Point }. Partition key: sos_id. Accepted mechanic consumer delivers push: "Your bid was accepted — head to user location.". (2) Publish to topic sos.bid.rejected — payload: { event_type: "BID_REJECTED", sos_id, bid_ids: [<all other bid_ids>] }. Partition key: sos_id. Rejected mechanic consumer delivers push: "Another mechanic was selected.". (3) Update accepted mechanic is_available = false (done inside the DB transaction in Step 5, or via a dedicated Kafka consumer on sos.bid.accepted). |

#### 4.1.4 Mechanic Withdraws Own Bid — DELETE /api/sos/{id}/bids/{bid_id}
A mechanic may withdraw a PENDING bid before the user has accepted it.
This is a soft-delete: the bid row is retained but bid_status is set to EXPIRED.

| Auth | Success Response | Error Responses |
| :--- | :--- | :--- |
| Bearer token. Role: Mechanic. bid.mechanic_id must match token.mechanic_id. | 200 OK — { bid_id: UUID, bid_status: "EXPIRED" } | 401 Unauthorized \| 403 Forbidden (bid does not belong to caller) \| 404 Not Found \| 409 Conflict (bid is already ACCEPTED — cannot withdraw after user accepted it) |

### 4.2 Kafka Event Design
The SOS backend uses Kafka as its internal event bus. REST endpoints perform DB writes, then publish Kafka events.
Downstream consumers handle notifications, availability updates, and any future integrations (analytics, audit logs, etc.) without coupling them to the API layer.

#### 4.2.1 Topic Inventory
| Topic | Partition Key | Producer | Purpose / Consumers |
| :--- | :--- | :--- | :--- |
| sos.created | sos_id | SOS API | Notification Consumer fans out to nearby mechanics via FCM/APNS or WebSocket. Analytics Consumer logs the event. |
| sos.bid.created | sos_id | SOS API | Notification Consumer alerts the SOS owner that a new bid arrived. |
| sos.bid.accepted | sos_id | SOS API | Notification Consumer pushes “bid accepted” to the winning mechanic. Mechanic Availability Consumer sets mechanic.is_available = false. |
| sos.bid.rejected | sos_id | SOS API | Notification Consumer pushes “bid rejected” to all losing mechanics. |
| sos.status.updated | sos_id | SOS API | Notification Consumer relays ON_THE_WAY / ARRIVED / COMPLETED status to the user in real time. Mechanic Availability Consumer re-sets is_available = true on COMPLETED. |

#### 4.2.2 Event Schemas
All events share a common envelope. event_type acts as a discriminator so a single consumer can handle multiple event types from one topic if needed.

| Topic | JSON Payload Fields |
| :--- | :--- |
| sos.created | event_type: "SOS_CREATED", event_id: UUID, occurred_at: ISO timestamp, sos_id: UUID, user_id: UUID, vehicle_id: UUID, problem_type: string, description: string\|null, location: GeoJSON Point |
| sos.bid.created | event_type: "BID_SUBMITTED", event_id: UUID, occurred_at: ISO timestamp, bid_id: UUID, sos_id: UUID, mechanic_id: UUID, mechanic_name: string, eta_minutes: integer, estimated_price: decimal\|null, note: string\|null |
| sos.bid.accepted | event_type: "BID_ACCEPTED", event_id: UUID, occurred_at: ISO timestamp, bid_id: UUID, sos_id: UUID, mechanic_id: UUID, user_id: UUID, location: GeoJSON Point |
| sos.bid.rejected | event_type: "BID_REJECTED", event_id: UUID, occurred_at: ISO timestamp, sos_id: UUID, bid_ids: UUID[] (all losing bids for this SOS) |
| sos.status.updated | event_type: "SOS_STATUS_UPDATED", event_id: UUID, occurred_at: ISO timestamp, sos_id: UUID, mechanic_id: UUID, user_id: UUID, from_status: string, to_status: string |

#### 4.2.3 Consumer Groups
| Consumer Group | Subscribes To | Responsibility |
| :--- | :--- | :--- |
| notification-service | sos.created, sos.bid.created, sos.bid.accepted, sos.bid.rejected, sos.status.updated | Delivers FCM/APNS push and/or WebSocket frames to the relevant user or mechanic device(s). On sos.created, queries nearby mechanics first then fans out. |
| mechanic-availability-service | sos.bid.accepted, sos.status.updated | Sets mechanic.is_available = false on BID_ACCEPTED. Resets to true when to_status = COMPLETED on sos.status.updated. |

#### 4.2.4 Delivery Guarantees & Error Handling
| Producer guarantee | Consumer guarantee | Dead-letter topic | Ordering |
| :--- | :--- | :--- | :--- |
| acks=all, retries enabled. Publish happens after the DB transaction commits to avoid ghost events on rollback. Use the Transactional Outbox pattern if strict at-least-once delivery is required. | At-least-once. Consumers must be idempotent (deduplicate on event_id). Commit offset only after successful processing. | sos.dlq — failed events land here after max retries. Alert on-call; retry or replay manually after root-cause fix. | Partitioning by sos_id ensures all events for one SOS land on the same partition and are processed in order by each consumer group. |

## 5. Glossary
| Term | Definition |
| :--- | :--- |
| PK | Primary Key – uniquely identifies each row in a table |
| FK | Foreign Key – references the PK of another table to enforce referential integrity |
| SOS Request | An emergency roadside assistance request submitted by a user |
| bid_sos | Bidding table linking Sos_request and Mechanic; records each mechanic’s offer (price, ETA, note) and its status (PENDING, ACCEPTED, REJECTED, EXPIRED) before one bid is formally accepted and triggers assignment |
| Mechanic | A registered service provider capable of handling SOS requests |
| Specialization | A category of mechanical expertise (e.g. tyre repair, engine diagnostics) |
| Status History | An immutable audit log of every status transition for an SOS request |
| Feedback | A post-service review (rating + comment) submitted by the user |
| is_available | A boolean flag on the Mechanic entity indicating current availability |
| rating_avg | Denormalized average of all feedback ratings for a mechanic |
| MVP | Minimum Viable Product – the smallest set of features to make the system functional |
| NFR | Non-Functional Requirement – quality attributes like performance, security, scalability |
| WebSocket | Protocol enabling real-time bidirectional communication between client and server. In this system WebSocket acts as the last-mile delivery layer; Kafka carries events between backend services. |
