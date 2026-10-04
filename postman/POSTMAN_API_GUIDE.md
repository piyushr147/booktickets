# Booktickets API — step-by-step Postman and cURL guide

## Deployed service

Base URL: https://booktickets-uee9.onrender.com

Every cURL below already uses this deployed base URL. The commands are formatted for Postman's cURL importer:

1. In Postman, click Import.
2. Choose Raw text.
3. Paste one complete cURL command from this guide.
4. Click Continue, then Import.
5. Review the generated request and click Send.

The cURL blocks use single quotes around arguments. Import them into Postman as shown. To run a block in a shell, use a shell-compatible version of the command; PowerShell users should run curl.exe, not the PowerShell curl alias.

## Fastest end-to-end Postman run

The included collection is postman/Booktickets API.postman_collection.json. It already targets the deployed base URL and has scripts that capture tokens and generated IDs, so you only need to enter the two mint secrets once.

### Step 1 — Import the collection

In Postman, choose Import > File, select Booktickets API.postman_collection.json, then click Import.

The collection request URLs already contain the deployed base URL, so no base URL setup is needed.

Set these two collection variables to the corresponding values configured in the Render service's Environment page:

- adminMintSecret = M64S0SRpseMp5a-zOZfReH_bHSrOzG9FOxH929sN99k
- userMintSecret = ItbOqFPpk6RwBLs0T1cflDo8K9D3sjXz0DVB05kWQx8

Do not share or commit these secrets. The remaining variables are populated by request scripts as you run the collection.

If either mint secret is not configured in Render, the matching mint endpoint returns 404 token_mint_disabled. If the value is wrong, it returns 401 invalid_mint_secret. Token minting must be enabled on the deployed service for the protected flow below.

### Step 2 — Check that the deployment is ready

Run these requests in the Health and metrics folder:

1. Liveness — expect 200 and {"status":"UP"}.
2. Readiness — expect 200 and {"status":"UP","dependency":"postgres"}.
3. Actuator health — expect 200 with status UP.
4. Actuator liveness probe — expect 200 with status UP.
5. Actuator readiness probe — expect 200 with status UP.
6. Prometheus metrics — expect 200 and Prometheus text.

Render may put an idle service to sleep. The first request can take longer while it starts. If readiness returns 503, check the Render application logs and PostgreSQL connection settings before testing reservations.

### Step 3 — Mint the admin and user tokens

Run Mint admin token, then Mint user token in the Authentication / token minting folder.

The collection stores each response's access_token automatically as adminToken and userToken. Tokens expire after one hour. Do not copy the JWTs manually when using the collection.

### Step 4 — Create a show

Run Create show (ADMIN; saves showId) in the Shows folder. It uses the admin token and generates a unique show name.

Expected: 201 Created. The collection saves the returned show id as showId and creates an idempotency key for the reservation.

### Step 5 — Check the show

Run Get show and seat availability. Expected: 200 OK with A1, A2 and A3 available.

### Step 6 — Reserve a seat and verify idempotency

Run Reserve seat (USER; saves reservationId). Expected: 201 Created. The collection stores reservation_id automatically.

Run Retry same reservation (idempotent replay) immediately afterward. It sends the same seat list and Idempotency-Key. Expected: 201 and response header Idempotency-Replayed: true.

### Step 7 — Cancel and verify the seat is available

Run Cancel reservation (owner USER). Expected: 200 with status cancelled.

Run Get show and seat availability again. A1 should now be available.

## Direct cURL requests for all endpoints

Replace only the uppercase PASTE_* placeholders before importing a protected request. For requests that need an ID, copy it from the preceding response. The complete collection flow above saves these values automatically.

### 1. Liveness — GET /health/live

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/health/live'
~~~

Expected: 200 OK, {"status":"UP"}.

### 2. Readiness and PostgreSQL — GET /health/ready

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/health/ready'
~~~

Expected when the database is reachable: 200 OK, {"status":"UP","dependency":"postgres"}. If PostgreSQL is unavailable, expect 503.

### 3. Actuator health — GET /actuator/health

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/actuator/health'
~~~

Expected: 200 OK with status UP.

### 4. Actuator liveness — GET /actuator/health/liveness

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/actuator/health/liveness'
~~~

Expected: 200 OK with status UP.

### 5. Actuator readiness — GET /actuator/health/readiness

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/actuator/health/readiness'
~~~

Expected: 200 OK with status UP when the readiness checks pass.

### 6. Prometheus metrics — GET /actuator/prometheus

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/actuator/prometheus'
~~~

Expected: 200 OK with Prometheus text metrics.

### 7. Mint an ADMIN token — POST /auth/token

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/auth/token' \
  --header 'X-Token-Mint-Secret: M64S0SRpseMp5a-zOZfReH_bHSrOzG9FOxH929sN99k' \
  --header 'Content-Type: application/json' \
  --data-raw '{"user_id":"postman-admin","role":"ADMIN"}'
~~~

Expected: 200 OK with access_token, token_type Bearer and expires_in 3600. Copy access_token if you are using cURL requests manually.

### 8. Mint a USER token — POST /auth/token

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/auth/token' \
  --header 'X-Token-Mint-Secret: ItbOqFPpk6RwBLs0T1cflDo8K9D3sjXz0DVB05kWQx8' \
  --header 'Content-Type: application/json' \
  --data-raw '{"user_id":"postman-user","role":"USER"}'
~~~

Expected: 200 OK with access_token, token_type Bearer and expires_in 3600. The role can be omitted; it defaults to USER.

### 9. Mint multiple USER tokens — POST /auth/tokens

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/auth/tokens' \
  --header 'X-Token-Mint-Secret: ItbOqFPpk6RwBLs0T1cflDo8K9D3sjXz0DVB05kWQx8' \
  --header 'Content-Type: application/json' \
  --data-raw '{"user_ids":["postman-user-1","postman-user-2"]}'
~~~

Expected: 200 OK with a tokens object keyed by user ID. Supply 1–5000 IDs, each 1–200 characters.

### 10. Create a show — POST /shows

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/shows' \
  --header 'Authorization: Bearer PASTE_ADMIN_ACCESS_TOKEN_HERE' \
  --header 'Content-Type: application/json' \
  --data-raw '{"name":"postman-curl-show","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}'
~~~

Expected: 201 Created, a Location header, and a show response containing id, total_seats, available, held, confirmed and seats. Copy id from the response for the next requests. Use a fresh show name if you want a separate show on a repeated run.

Limits: seats must contain 1–20,000 unique labels; each label can be at most 32 characters. price_paise must be non-negative. per_user_limit is optional, defaults to 4, and must be from 1 to 100.

### 11. Get a show — GET /shows/{showId}

Replace PASTE_SHOW_ID_HERE with the id returned by Create a show.

~~~sh
curl --request GET 'https://booktickets-uee9.onrender.com/shows/PASTE_SHOW_ID_HERE'
~~~

Expected: 200 OK with show details and current seat states. This endpoint is public; no bearer token is required.

### 12. Reserve seats — POST /shows/{showId}/reserve

Replace PASTE_SHOW_ID_HERE and PASTE_USER_ACCESS_TOKEN_HERE. The Idempotency-Key must be unique for a new operation. To verify replay, resend the exact same request with the same key and body.

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/shows/PASTE_SHOW_ID_HERE/reserve' \
  --header 'Authorization: Bearer PASTE_USER_ACCESS_TOKEN_HERE' \
  --header 'Idempotency-Key: postman-curl-reservation-001' \
  --header 'Content-Type: application/json' \
  --data-raw '{"seats":["A1"]}'
~~~

Expected for the first request: 201 Created with reservation_id, show_id, user_id, seats, amount_paise and status confirmed. Copy reservation_id for cancellation.

The authenticated token determines user_id. Send 1–50 unique seat labels. Repeating the same key and body replays the original result and returns Idempotency-Replayed: true.

### 13. Cancel a reservation — POST /reservations/{reservationId}/cancel

Replace PASTE_RESERVATION_ID_HERE and use the USER token belonging to the reservation owner. No request body is required.

~~~sh
curl --request POST 'https://booktickets-uee9.onrender.com/reservations/PASTE_RESERVATION_ID_HERE/cancel' \
  --header 'Authorization: Bearer PASTE_USER_ACCESS_TOKEN_HERE'
~~~

Expected: 200 OK with {"reservation_id":"...","status":"cancelled"}. Cancelling again is harmless and returns 200. A reservation owned by another user is reported as not found.

## Common errors

API errors use JSON with code, message, request_id, and sometimes seats.

- 400: invalid input, seat list, or idempotency key.
- 401: bearer token or token-mint secret is missing or invalid.
- 403: token role is not authorized for that operation.
- 404: show/reservation not found, or token minting is disabled.
- 409: seat already taken, per-user show limit reached, or idempotency key reused with a different request.
- 422: seat label does not exist or amount overflow.
- 503: database unavailable.

Use the included collection for the end-to-end test because it captures JWTs and IDs for you. The direct cURL requests are useful for importing an individual request into Postman or checking one endpoint at a time. Reserve and cancel requests write to the deployed database.