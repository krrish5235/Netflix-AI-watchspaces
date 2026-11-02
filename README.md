# Social Watch Space API

Backend-only Java service for synchronized social viewing rooms. The service is
implemented with Java 17, Spring Boot 3.3, Spring WebFlux, Spring Security,
JWT authentication, and a typed WebSocket room protocol.

The product scope comes from the supplied PRD document. There is intentionally
no production frontend in this repository. A separate static presentation-only
showcase lives under [`showcase/`](./showcase/); it makes no API calls and is
not part of the backend runtime.

## Current backend slice

Implemented flows (verification scope is described below):

1. Register, log in, and refresh JWT access tokens.
2. Create an authenticated Watch Space with a secure invite code.
3. Join by room id or invite code, with participant limits and membership
   checks.
4. Start, lock, unlock, and end a room as its host.
5. Leave, remove a participant, or transfer hosting with server-side checks.
6. Connect authenticated clients to `/api/v1/ws/watch-spaces/{id}`.
7. Exchange validated JSON events for presence, chat, ping/pong, and
   host-authorized play/pause/seek commands.
8. Read the seeded `demo-title` timeline and ask membership-protected questions
   that return excerpts with source-event citations, or an insufficient-context
   response when retrieval finds no matching event.

Default-profile repositories store accounts, rooms, participants, titles, and
timeline events through JDBC. Flyway V1 and V2 create the schema and seed a
fictional demo timeline. Room mutations use database row locks and transactions;
reads return detached snapshots rather than mutable cached rooms.

This is still a partial implementation, **not a production-ready release**.
WebSockets forward commands but do not maintain synchronized playback state.
Answers use deterministic keyword retrieval, not Gemini or another live model.
Database checks have run against H2 in PostgreSQL mode, not a PostgreSQL server.

## Requirements

- JDK 17+
- Maven 3.8.7+ (the container build uses 3.9.9)
- A JWT secret containing at least 32 UTF-8 bytes
- PostgreSQL 14+ for the default application profile

## Run locally

Create an empty PostgreSQL database named `watchspaces` and a database user
with migration privileges. Export configuration and start the service:

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
export DATABASE_URL='jdbc:postgresql://localhost:5432/watchspaces'
export DATABASE_USERNAME='watchspaces'
export DATABASE_PASSWORD='<your-local-database-password>'
mvn spring-boot:run
```

Flyway applies the schema in `src/main/resources/db/migration` at startup. The
API listens on `http://localhost:8080` by default. Maven/Spring Boot does not
automatically load `.env`; export those variables in the launching terminal.
Never commit real credentials. The `test` profile uses in-memory stores, while
database tests use isolated H2 databases. Do not enable `test` in a deployment.

Alternatively, with Docker and Compose installed, export `JWT_SECRET` and a
unique `DATABASE_PASSWORD`, then run:

```bash
docker compose up --build
```

`compose.yaml` binds the API and database to localhost and stores database data
in a named volume. It is a local-development setup, not a hardened deployment.
The Docker image and Compose startup have not been exercised in this workspace.

Run the checks from the project root:

```bash
mvn -B clean verify
```

Latest combined check on 2026-10-21: **95 tests, 0 failures, 0 errors, 0 skipped**;
executable JAR created at `target/social-watch-space-api-0.1.0-SNAPSHOT.jar`.

Coverage includes HTTP registration/create/join/host authorization, room
transitions and concurrent capacity, JDBC rollback/read-back, migration and
timeline filtering, citation retrieval, and real multi-client WebSocket tests.
Tests also check that synchronous HTTP password/store work and WebSocket
repository calls run outside reactive event-loop threads.

No separate lint, dependency security scan, native PostgreSQL restart test,
Docker build, or load/latency benchmark has been run. H2 emits a Flyway version
compatibility warning despite passing the migration checks. Passing these
tests is not evidence of production readiness.

## HTTP API

All endpoints below use the `/api/v1` prefix. Except for health and
authentication endpoints, send an access token in the `Authorization: Bearer
<token>` header.

### Authentication

```text
POST /auth/register
POST /auth/login
POST /auth/refresh
```

Registration creates a `VIEWER` account. The account that creates a room is
the room host; the global account role is not trusted as room ownership.

### Watch Spaces

```text
POST   /watch-spaces
GET    /watch-spaces/{id}
POST   /watch-spaces/{id}/join
POST   /watch-spaces/join-by-code
POST   /watch-spaces/{id}/start
POST   /watch-spaces/{id}/end
POST   /watch-spaces/{id}/lock
POST   /watch-spaces/{id}/unlock
POST   /watch-spaces/{id}/leave
DELETE /watch-spaces/{id}/participants/{participantId}
POST   /watch-spaces/{id}/transfer-host/{newHostId}
```

Only members can read a room. Host operations are authorized by the room's
server-side `hostUserId`; a client cannot promote itself by changing a JWT
claim or request body. Invalid lifecycle transitions return a conflict error,
and non-members receive a forbidden response.

### WebSocket protocol

Connect using `Authorization: Bearer <access-token>` in the upgrade request.
Query-string tokens are rejected to avoid credentials in URLs/logs. Use a
backend client such as Java HttpClient or an API tool that supports handshake
headers; native browser WebSockets cannot use this authentication flow.

```text
GET /api/v1/ws/watch-spaces/{id}
```

Clients must join before sending room events:

```json
{"type":"join","requestId":"join-1"}
{"type":"chat","requestId":"chat-1","message":"Hello"}
{"type":"ping","requestId":"ping-1"}
{"type":"play","requestId":"play-1"}
{"type":"pause","requestId":"pause-1"}
{"type":"seek","requestId":"seek-1","positionSeconds":42.5}
{"type":"leave","requestId":"leave-1"}
```

Messages are parsed into a closed set of typed events. Payloads, request ids,
chat length, and seek values are bounded before they reach the room state
machine. Host-only playback commands are rejected with `HOST_REQUIRED`, and
malformed messages receive stable `error` events rather than server errors.

Queues are bounded to 64 messages per connection; overflow closes the slow
connection with code 1013. Current membership and host ownership are rechecked
for commands and queued room-event delivery. A removed participant or ended
room closes with 1008 when detected on the next command/delivery; idle sockets
are not proactively evicted. Already-authorized in-flight frames cannot be
recalled. A WebSocket `leave` removes live presence, not persisted membership;
use the REST leave endpoint to leave membership. Removal is not a permanent
ban, so rejoining through the normal API remains possible.

The registry is process-local. Chat is not persisted, playback commands are
not stored/replayed, and there is no authoritative clock, drift correction,
automatic host-disconnect policy, or reconnect resynchronization yet.

### Timeline and question API

```text
GET  /titles/{titleId}/timeline?from=0&to=45
POST /watch-spaces/{id}/ai/ask
```

Use `demo-title` when creating a room to exercise the seeded content. Timeline
range endpoints are inclusive; `from` and `to` are optional. Example question:

```json
{"question":"Who is Detective Rios?","currentPositionSeconds":22.0}
```

The answer includes text, up to three citations (event ID and source label),
`confidence`, and `generatedAt`. Confidence is an uncalibrated heuristic, not a
probability that the answer is correct. If a position is supplied, retrieval
uses the preceding 120 seconds without future events. If omitted, retrieval
can see the entire timeline; the position is client-supplied, not authoritative.
Model generation and admin timeline uploads are not implemented. The Gemini
environment settings are reserved and currently unused.

## Architecture and delivery order

The service is organized into API contracts, domain state, application
services, security, and transport adapters. The intended production sequence
after this slice is:

1. Verify native PostgreSQL migrations, rollback, concurrency, and data survival
   across application restart. Docker/PostgreSQL executables were unavailable
   in the current container; no live-database test is claimed.
2. Implement authoritative playback state, reconnect snapshots, clock sync,
   host-disconnect rules, and measured multi-client drift. Add cross-instance
   fan-out before attempting horizontal scaling.
3. Harden authentication: refresh-token rotation/revocation and logout,
   rate limiting, password edge cases, role provisioning/admin enforcement,
   account/token revocation on active sockets, and consistent error responses.
   Present host checks are room-ownership checks, not complete global RBAC.
4. Add validated admin timeline ingestion and real grounded model integration;
   persist chat/interactions/session summaries. Add catalog-title validation
   when creating rooms (currently any nonblank title ID is accepted).
5. Implement timeline-triggered trivia, approved subtitle/narrative variations,
   voting, analytics, and recommendations. Current room settings alone do not
   implement those features.
6. Add OpenAPI and client examples, CI, supported-dependency/security checks,
   structured observability, deployment/backup guidance, and load tests.

These are deliberately listed as remaining work instead of being represented
by placeholder endpoints. A production service must persist data and prove
the behavior with integration checks before those features are considered
complete.

## Session handoff

The initial workspace contained authentication and in-memory room create/get/
join plus two tests. This session added lifecycle/ownership rules, the initial
WebSocket gateway, deterministic timeline retrieval, JDBC stores and migrations,
Docker setup files, and regression/integration tests. The supplied DOCX remains
unchanged. Fixed requirements: **backend only, Java, production-quality target**.

Resume with native PostgreSQL verification before adding more endpoints. The
remaining list above is part of the work, not a claim that the full PRD is done.
