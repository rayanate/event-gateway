# eventGateway

A small Spring Boot service that stores Event records in an H2 database and demonstrates graceful degradation when an external Account Service is unavailable.

## Architecture overview

- Spring Boot application exposing REST endpoints under `/events`.
- JPA entity `EventRecord` persisted to an embedded H2 database (in-memory).
- Metadata field is stored as JSON via a JPA AttributeConverter.
- POST /events validates the event and calls an external Account Service before persisting (apply-first-then-persist).
- External Account Service calls are protected by Resilience4j (Circuit Breaker + TimeLimiter) with a fallback that returns HTTP 503 and increments a custom metric.
- Tracing is done via a simple `X-Trace-Id` propagation (MDC + RestTemplate interceptor) and logs are emitted in JSON (logstash encoder) including the trace id.
- Actuator exposes `/health` and `/metrics` for liveness and metrics (Micrometer).

## Project layout (important files)

- `src/main/java/.../model/EventRecord.java` — JPA entity
- `src/main/java/.../repository/EventRecordRepository.java` — JPA repo
- `src/main/java/.../controller/EventRecordController.java` — REST controller
- `src/main/java/.../service/AccountServiceClient.java` — Resilience4j-wrapped Account Service client
- `src/main/resources/application.properties` — configuration (ports, H2, resilience4j)
- `stub_account_service.py` — lightweight Python stub for Account Service used in local testing
- `docker-compose.yml` — brings up the stub + the event-gateway
- `Dockerfile` — multi-stage Dockerfile to build and run the service

## Prerequisites

- Java 17 (JDK)
- Maven (or use the included Maven Wrapper `mvnw.cmd` / `./mvnw`)
- Docker + Docker Compose (if running with Docker)
- Python 3 (optional, used if running the stub manually instead of via Docker)

## Build

From the project root (where `pom.xml` is located):

```powershell
# Windows PowerShell
.\mvnw.cmd -DskipTests package
```

Or (Linux/macOS):

```bash
./mvnw -DskipTests package
```

## How to start both services

### Option A — Docker Compose (recommended)

This will start:
- a stub Account Service on port `8082`
- the event-gateway on port `8080`

```bash
docker-compose up --build
```

Then:
- Event Gateway: `http://localhost:8080`
- H2 Console: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:eventdb`, user `sa`, empty password)
- Health: `http://localhost:8080/health`
- Stub Account Service: `http://localhost:8082/accounts/{id}`

To stop:

```bash
docker-compose down
```

### Option B — Manual (without Docker)

1. Start the stub Account Service (in a separate terminal):

```bash
python stub_account_service.py --port 8082
```

2. Start the Spring Boot app from the project root:

```powershell
# Windows
.\mvnw.cmd spring-boot:run
```

Or (Linux/macOS):

```bash
./mvnw spring-boot:run
```

The service will listen on port `8080`.

> Note: The Account Service base URL used by the application is configured via the property `account.service.url` in `src/main/resources/application.properties`. You can override it with the environment variable `ACCOUNT_SERVICE_URL` (Docker Compose uses this to point to the `account-service` container).

## Endpoints

- GET /events?account={accountId}  — list events (sorted by eventTimestamp desc)
- GET /events/{id}                 — fetch single event by id
- POST /events                     — create an event (apply -> account service -> persist)
- GET /health                      — actuator health
- Metrics are available under actuator endpoints (`/metrics`) per configuration

Prometheus endpoint:
- `/actuator/prometheus` — Prometheus scrape endpoint (requires `micrometer-registry-prometheus` which is included)

Example POST JSON body:

```json
{
  "accountId":"acct-1",
  "metadata":{"foo":"bar"}
}
```

When `eventId` and `eventTimestamp` are omitted, the server will generate an `eventId` (UUID) and set `eventTimestamp` to now.

## How to run the tests

From the project root:

```powershell
# Windows
.\mvnw.cmd test
```

Or (Linux/macOS):

```bash
./mvnw test
```

The integration tests start the application on a random port and mock the Account Service (no external dependency required).

## Resiliency pattern explanation

We use Resilience4j with two cooperating patterns:

1. Circuit Breaker (Resilience4j):
   - Prevents the gateway from repeatedly attempting calls to an unhealthy Account Service.
   - When the failure rate exceeds a configured threshold over a sliding window, the circuit opens and calls are short-circuited immediately.

2. Time Limiter (Resilience4j):
   - Ensures slow responses don't tie up request threads. Calls that exceed the configured timeout are treated as failures and can contribute to tripping the circuit.

Fallback behavior:
- If the Account Service call fails, times out, or the circuit is open, the gateway returns HTTP 503 Service Unavailable for the POST request and increments a Micrometer counter `events.accountService.fallback`.
- Reads (GET) are implemented to touch only the local H2 store and therefore continue to work even while writes are being shed — this is the graceful-degradation guarantee: read traffic remains available while write traffic is shed under Account Service failure.

Why this choice?
- Circuit Breaker + Time Limiter is a common pragmatic combination: TimeLimiter causes quick failure for slow downstreams; Circuit Breaker stops repeated attempts to a failing dependency, protecting system resources.
- A short-circuit fallback that returns 503 is appropriate for write operations that must be validated by Account Service — it signals clients to retry later without overloading the system.

## Tracing & Logging

- The app propagates a simple `X-Trace-Id` header from incoming requests and forwards it to the Account Service via RestTemplate interceptor. The same `traceId` is emitted in JSON logs.
- JSON logs are configured via `logback-spring.xml` and include the trace id in the output making it easy to correlate requests across services.

## Configuration

Key properties (in `src/main/resources/application.properties`):

- `server.port` — service port (default 8080)
- `account.service.url` — base URL of Account Service (default `http://localhost:8082` in this repo)
- Resilience4j settings for the `accountService` instance

You can override `account.service.url` with the environment variable `ACCOUNT_SERVICE_URL`.

---

If you want, I can:
- Add an automated test that simulates Account Service failures and asserts POST returns 503 and the fallback metric increments.
- Start `docker-compose up --build` for you here to demonstrate the running stack.
- Add Prometheus support to expose metrics in a Prometheus-friendly format.


