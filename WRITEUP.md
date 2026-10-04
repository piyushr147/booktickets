# Seat Reservation Design Notes

## Atomic decision and lock order

PostgreSQL is the system of record. Shows, seats, reservations, reservation-seat history, quotas, and idempotency records are mapped as JPA entities and accessed through Spring Data repositories. A reservation is one `READ COMMITTED` transaction. Repository methods apply pessimistic row locks: first the `(show_id, user_id)` quota row, then all requested seat rows ordered by label. Narrow native repository inserts use `ON CONFLICT DO NOTHING` to safely initialize quota and idempotency rows under concurrent requests. The service verifies the entire request before writing reservation state, then updates seats, quota, response and history in the same transaction.

Every booking locks the quota row before seat rows. The sorted seat-label order means overlapping multi-seat requests acquire seats in the same order. Cancellation uses the same quota-before-seat order. This avoids a cycle between one transaction holding a quota row while waiting on a seat and another holding that seat while waiting on the quota. The `(show_id, label)` primary key is the final uniqueness guard.

The quota row is serialized per user and show, so two requests from the same user cannot both pass the four-seat default. The counter includes only currently confirmed seats. Cancellation decrements it in the same transaction that releases the seats.

Requests for multiple seats are all-or-nothing. Unknown labels return `422`; an unavailable seat or an exceeded per-user limit returns `409`. Expected contention is checked while the rows are locked and is a normal response, not an exception.

## Idempotency

`idempotency_keys` has a primary key on `(user_id, key)`. The request hash covers the show ID and a sorted, normalized seat list. A repository `INSERT ... ON CONFLICT DO NOTHING` makes simultaneous retries wait for the transaction that first claimed the key. The entity stores the response status and body alongside the reservation decision. Same key and same body returns the stored response; same key with a different show or seat set returns `409 idempotency_conflict` without changing the original record. Declines are also stored so that retrying a declined attempt cannot become a new booking after inventory changes.

The exercise has no payment provider call. A future payment flow would create an idempotent payment intent and use an outbox or saga; it would not call the provider while holding the seat transaction open.

## Holds and cancellation

Reservations confirm immediately, as in the API contract, and an owner can cancel through `POST /reservations/{id}/cancel`. There is no expiry sweeper to race against cancellation or confirmation. A release only updates seat rows whose `reservation_id` still matches the reservation being canceled. Repeated cancellation is a no-op. A future timed hold would add `expires_at` and a sweeper that takes the same quota and ordered seat locks before a guarded release.

## Identity and database consistency

The booking DTO has no user identity field. Spring Security verifies an HS256 JWT and takes the user ID from `sub`; admin show creation requires the `ADMIN` role. The token mint endpoints are unavailable unless separate user and admin mint secrets are configured. Public deployments must use fresh secrets and limit access to the admin mint secret.

The application uses one PostgreSQL primary. During a database outage it fails closed and readiness returns `503`; it does not accept bookings from a cache. This favors consistency over write availability during a partition. Seat rows are canonical, and `/shows/{id}` reads mapped entities in a repeatable-read transaction to keep the seat list and its counts on one database snapshot. The invariant is `available + held + confirmed = total_seats`; the selected cancellation model currently keeps `held` at zero.

## Observability and operations

`/health/live` reports process liveness. `/health/ready` borrows a connection with a short acquisition timeout and runs `SELECT 1`. `/actuator/prometheus` exposes the confirmation and decline counters, request outcome counters, reservation latency histogram, and `seats_available{show_id}` gauges refreshed from PostgreSQL about once per second. Confirmation and decline counters are incremented after transaction commit so rollbacks do not inflate them. The `idempotent_replay` reason is also emitted as a request outcome; it can replay an original 201 and should not be interpreted as an HTTP decline.

Structured stdout logs include request ID, path, HTTP status, authenticated user, outcome, and latency. Tokens and request bodies are not logged. Useful 2am alerts include any sustained 5xx, failed readiness, Hikari pool saturation, rising p99 reservation latency, and a reconciliation mismatch in the burst verifier.

The 20,000-request scenario still has a finite database and host. Virtual threads keep waiting request tasks lightweight, but do not increase PostgreSQL capacity. Pool size, request concurrency, connection timeout, and host memory must be measured together. The hot seat remains a serial decision point by design; the system preserves correctness if contention lowers throughput.

## Container and deployment

The multi-stage Dockerfile builds with Maven and runs a Java 21 JRE image as a non-root user. Compose runs that same app with PostgreSQL and a persistent local volume. JPA is configured to validate (not generate) the Flyway-managed schema and batch inserts for bulk seat creation. In deployment, use a managed PostgreSQL database and set `DATABASE_URL`, `JWT_SECRET`, and both token-mint secrets through the host's secret manager. Platform URLs in `postgres://` format are converted to JDBC form. Probe readiness at `/health/ready`, and validate a cold start before running the public burst.

## AI use and next work

AI assistance was used to organize the execution plan, draft the schema and concurrency flow, and scaffold implementation and verification cases. The developer should review every SQL statement and be able to explain the lock ordering, transaction boundaries, idempotency behavior, failure mapping, and cancellation race before submitting; only claim implementation decisions that have been personally reviewed and tested.

Next steps are timed holds with payment authorization, rate limiting and queueing policy tuned from live burst data, and a read replica for show-state reads if the write primary becomes the bottleneck. A cache or Redis path should not become a second authority for seat ownership.
