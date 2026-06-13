# Run & Test Instructions — Event Gateway + Account Service

This document lists step-by-step commands (PowerShell / Windows) to run and test both services locally. It includes both manual (local JVM + Maven) steps and Docker Compose steps.

Prerequisites
- Java 17 (or compatible JDK)
- Maven (optional if using `mvnw` wrapper included)
- Docker & Docker Compose (optional for containerized run)
- curl (or use Postman)


1) Run services locally (no Docker)

A. Start the Account Service JAR (bundled in repo)

PowerShell:

```powershell
# From the repository root
java -jar .\src\main\resources\accountService-0.0.1-SNAPSHOT.jar
```

Default: the account service listens on port 8082 and uses an in-memory H2 DB (jdbc:h2:mem:testdb). The H2 console is available at: http://localhost:8082/h2-console (user: sa, password: password).

B. Start the Event Gateway using the Maven wrapper

PowerShell:

```powershell
# From the repository root
.\mvnw clean package -DskipTests
java -jar .\target\eventGateway-0.0.1-SNAPSHOT.jar
```

The gateway listens on port 8080 and uses in-memory H2 DB (jdbc:h2:mem:eventdb). H2 console: http://localhost:8080/h2-console (user: sa, password: <blank>). If you want to run from the IDE instead, run the Spring Boot application class `EventGatewayApplication`.

C. Test the end-to-end flow

1. Check health endpoints

```powershell
curl http://localhost:8082/actuator/health
curl http://localhost:8080/actuator/health
```

2. POST an event to the gateway (ensure amount is in `metadata` per current implementation)

```powershell
curl -i -X POST http://localhost:8080/events `
  -H "Content-Type: application/json" `
  -d @- <<'JSON'
{
  "eventId": "evt-001",
  "accountId": "acct-e2e",
  "type": "CREDIT",
  "amount": 200.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T10:00:00Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042",
    "amount": 200.00
  }
}
JSON
```

3. Check persistence
- Gateway DB (Event records): open http://localhost:8080/h2-console and run `SELECT * FROM EVENT_RECORD;` (JDBC URL: `jdbc:h2:mem:eventdb`, user `sa`, blank password)
- Account Service DB (applied transactions): open http://localhost:8082/h2-console and run `SELECT * FROM APPLIED_TRANSACTIONS;` (JDBC URL: `jdbc:h2:mem:testdb`, user `sa`, password `password`)


2) Run both services via Docker Compose (recommended for isolation)

From the repository root run:

```powershell
docker-compose up --build
```

Notes:
- Compose will start `account-service` (running the bundled JAR on 8082) and `event-gateway` (built from this repo) on 8080.
- Health endpoints:
  - http://localhost:8082/actuator/health
  - http://localhost:8080/actuator/health
- H2 Consoles (same credentials as above)

To stop and remove containers:

```powershell
docker-compose down
```


3) Run tests (integration + unit)

Run with Maven wrapper:

```powershell
.\mvnw test
```

Note: integration tests expect to run in the local test environment (they mock the Account Service via `MockRestServiceServer`).


4) Troubleshooting tips
- If POST returns `503 Service Unavailable`, the gateway's Resilience4j fallback likely triggered. Check the gateway logs for `TimeLimiter`/`TimeoutException`/`CallNotPermittedException` messages.
- Confirm the account service is reachable: `curl http://localhost:8082/accounts/acct-e2e` and `curl -X POST http://localhost:8082/accounts/acct-e2e/transactions -H "Content-Type: application/json" -d '{"eventId":"evt-001","amount":200.00}'`
- Ensure the POST body contains `metadata.amount` (current gateway extracts amount from `metadata`). To change this behaviour, update `EventRecord` and controller to accept top-level `amount`.
- On Windows, if Docker Compose volume mounts fail for the account service JAR, consider building an image for the account service instead of mounting the file. I can add a Dockerfile and change the compose file accordingly if you prefer.


5) Environment variables and configuration
- The gateway reads `account.service.url` from `application.properties`, but the Compose file sets `ACCOUNT_SERVICE_URL` which Spring maps to the property at runtime.
- To change timeouts or circuit breaker settings, edit `src/main/resources/application.properties` (`resilience4j.timelimiter.instances.accountService.timeoutDuration` etc.).


---

If you want, I can:
- Add a Dockerfile for the account service and update `docker-compose.yml` to build it instead of mount the JAR (better portability on Windows).
- Modify the gateway to accept top-level `amount` in JSON and run the tests to confirm.

Which follow-up should I do next?
