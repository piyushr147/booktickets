# Seat Reservation Service

A Spring Boot REST API for creating shows and reserving assigned seats. Spring Data JPA entities and repositories map the domain to PostgreSQL; Flyway owns schema changes. PostgreSQL is the source of truth for seat state, per-user quota, reservation history, and idempotency. Reservations confirm immediately; the owner can cancel to return the seats to inventory.

## Run locally with Docker Compose

Requirements: Docker Desktop with Compose enabled. The default credentials and token-mint secrets in `compose.yaml` are for local development only.

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
```

Wait for the `app` health check, then verify:

```powershell
Invoke-RestMethod http://localhost:8080/health/live
Invoke-RestMethod http://localhost:8080/health/ready
```

Flyway creates the schema on startup. To run the Java integration and concurrency tests, use a running Docker daemon:

```powershell
.\mvnw.cmd test
```

The wrapper requires a writable Maven cache. If the PowerShell wrapper is unavailable, use a local Maven 3.9+ installation with `mvn test`.

## Mint local tokens

The token mint endpoints are disabled unless the matching secret is configured. The local Compose defaults enable them for this exercise. Mint an admin token and a user token:

```powershell
$admin = Invoke-RestMethod -Method Post http://localhost:8080/auth/token -Headers @{ 'X-Token-Mint-Secret' = 'local-admin-mint-secret-change-me' } -ContentType 'application/json' -Body '{"user_id":"local-admin","role":"ADMIN"}'
$user = Invoke-RestMethod -Method Post http://localhost:8080/auth/token -Headers @{ 'X-Token-Mint-Secret' = 'local-user-mint-secret-change-me' } -ContentType 'application/json' -Body '{"user_id":"alice"}'
```

Create a show, then reserve and inspect a seat:

```powershell
$show = Invoke-RestMethod -Method Post http://localhost:8080/shows -Headers @{ Authorization = "Bearer $($admin.access_token)" } -ContentType 'application/json' -Body '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}'
Invoke-RestMethod -Method Post "http://localhost:8080/shows/$($show.id)/reserve" -Headers @{ Authorization = "Bearer $($user.access_token)"; 'Idempotency-Key' = 'alice-friday-a1' } -ContentType 'application/json' -Body '{"seats":["A1"]}'
Invoke-RestMethod "http://localhost:8080/shows/$($show.id)"
```

`POST /shows` requires an `ADMIN` role. `POST /shows/{id}/reserve` and `POST /reservations/{id}/cancel` require a `USER` role. Identity always comes from the verified HS256 bearer token; fields such as `user_id` in the booking body are ignored. Token minting can issue users or admins only when the corresponding secret is configured. Never use the local fallback values on a public deployment.

## Run the burst

Install the script dependency once:

```powershell
python -m pip install -r scripts/requirements.txt
```

Set the base URL and the two mint secrets for the target environment, then run:

```powershell
$env:USER_TOKEN_MINT_SECRET = 'your-user-mint-secret'
$env:ADMIN_TOKEN_MINT_SECRET = 'your-admin-mint-secret'
python scripts/burst.py http://localhost:8080 --requests 20000 --concurrency 500
```

The script creates isolated shows and checks a 500-user hot-seat storm, a 20,000-request sale burst with 10% same-key retries, the per-user limit, same-key/different-body conflict, spoofed identity, and owner-only cancellation. It reports 201, 409 by reason, other 4xx, 5xx/network errors, and verifies API seat reconciliation and Prometheus counters/gauges. Increase `--concurrency` only after measuring the target host and database; request count and simultaneous client count are separate controls.

Metrics are available at `/actuator/prometheus`. Liveness is `/health/live`; readiness is `/health/ready` and runs a bounded `SELECT 1`. Logs are JSON on stdout and include `X-Request-Id`, user, outcome, status, path, and latency. Do not expose other Actuator endpoints.

## Deploy

Deploy the same Docker image to a container platform and use a managed PostgreSQL database. Set `DATABASE_URL` to either a PostgreSQL JDBC URL or a platform URL in `postgres://user:password@host:port/database?sslmode=require` form; the application converts the latter. Set `JWT_SECRET`, `USER_TOKEN_MINT_SECRET`, and `ADMIN_TOKEN_MINT_SECRET` as separate platform secrets. Do not run a database inside the application container in production.

The application starts Flyway migrations before serving requests. Configure the platform readiness probe to `/health/ready` and keep liveness on `/health/live`. After deployment, verify a cold start, create a show, run the burst against the public URL, and save the JSON logs or a short recording of the run. The free-tier host and database must be load-tested together; the database remains the serialized decision point for each hot seat.

## Design and status codes

- Multi-seat requests are all-or-nothing.
- Unknown seat labels return `422`; unavailable seats and user-limit violations return `409`.
- The first idempotency key use stores the outcome with the booking decision. A same-key/same-body retry replays that exact status and response; same key with a different show or seat set returns `409`.
- A successful reservation is confirmed immediately. Cancellation is owner-only, repeat cancellation is harmless, and canceled seats can be booked again with a new key.
- `price_paise` and `amount_paise` are signed 64-bit integer values; the server checks multiplication overflow.
- Spring Data repositories lock the user quota row first and seat rows in sorted label order. The unique seat primary key and transaction locks prevent duplicate sales and deadlocks for overlapping multi-seat requests. Native PostgreSQL `ON CONFLICT DO NOTHING` is limited to safely claiming idempotency and quota rows.

See [WRITEUP.md](WRITEUP.md) for the concurrency mechanism, limits, observability trade-offs, and AI-use disclosure.

## Code layout

- `controllers`: Spring MVC `@RestController` endpoints.
- `api/request`: incoming request records. Java components use camelCase; `@JsonProperty` preserves the snake_case API fields.
- `api/response`: response DTOs, including show, seat, reservation, and token responses.
- `exceptions`: API exceptions, error payload, and global `@RestControllerAdvice` mapping.
- `persistence/entity` and `persistence/repository`: JPA table mappings, relationships, and Spring Data repositories.
- `service`: transaction boundaries and booking/show use cases.
- `security`, `metrics`, and `observability`: authentication, instrumentation, and request logging.
