# wallet-api

A simple digital wallet REST API built with Spring Boot 4.1, Java 17, Maven and PostgreSQL.

## Features

- User registration and login with JWT authentication (Spring Security, stateless)
- Each user has exactly one wallet; money is stored as `BigDecimal` (`NUMERIC(19,4)`)
- Transfer funds to another user by username or by wallet id
- View your own wallet, or a specific wallet by id (owner or admin)
- View your transaction history, or a specific transaction by id (participants or admin)
- Simulated withdrawals (no payment provider). Money enters the system only through the admin deposit endpoint or an admin-approved deposit request: there is no self-service deposit and new wallets start at 0
- Deposit requests: a user asks for money to be added (for example after a bank transfer); the wallet is credited only when an admin approves
- Optional note on transfers
- Paginated, sortable, filterable transaction history and admin lists
- Profile management (view, change email, change password); admins can disable and enable users
- Token revocation: logout (all devices), password change and admin disable invalidate existing tokens
- Admin endpoints to list all users and all transactions
- Optional idempotency keys on transfers, deposits and withdrawals (scoped per user; a repeat must be the same request)

## Prerequisites

- JDK 17
- Maven 3.9+
- PostgreSQL 16 (any recent version should work) listening on `localhost:5432`

## Setup

1. Create the database (Flyway creates the tables when the app starts):

   ```
   psql -U postgres -h localhost -c "CREATE DATABASE walletdb"
   ```

2. Configure the app. `JWT_SECRET` is REQUIRED: there is no built-in default, and the app refuses to start without it
   (it prints "No JWT signing secret is configured"). The other variables have defaults suitable for local development only.

| Variable / property | Default | Purpose |
|---|---|---|
| `DB_USERNAME` | `postgres` | Database user (`spring.datasource.username`) |
| `DB_PASSWORD` | `postgres` | Database password (`spring.datasource.password`) |
| `JWT_SECRET` | none (required) | Base64-encoded HMAC secret (`app.jwt.secret`); must decode to at least 32 bytes. Not needed with `--spring.profiles.active=dev`, which supplies a public dev-only secret |
| `ADMIN_PASSWORD` | empty | Password for the bootstrap admin (`app.admin.password`), at least 12 characters and at most 72 bytes. If empty, no admin is created; if too weak, an error is logged and no admin is created |
| `app.jwt.expiration-ms` | `3600000` (1 hour) | Token lifetime |
| `app.wallet.initial-balance` | `0.00` | Balance given to every new wallet. Leave at 0 in real use; override it only for a local demo or tests |
| `app.wallet.currency` | `USD` | Currency of every new wallet |
| `app.admin.username` | `admin` | Username of the bootstrap admin |
| `app.admin.email` | `admin@example.com` | Email of the bootstrap admin |

The JDBC URL is `jdbc:postgresql://localhost:5432/walletdb` (edit `src/main/resources/application.properties` to change it). The `app.*` properties not backed by an environment variable can be overridden the usual Spring way, for example `--app.wallet.initial-balance=0` or the `APP_WALLET_INITIAL_BALANCE` environment variable.

## Run and build

Local development (PowerShell). The `dev` profile (`application-dev.properties`) supplies a publicly known JWT secret, so it
must never be used on a shared or real deployment. It is active only when you select it:

```
$env:ADMIN_PASSWORD = "choose-a-dev-password"   # at least 12 characters; needed to create the admin who funds wallets
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The `dev` profile also binds the server to `127.0.0.1`, so only the same machine can reach it. To reach it from another
machine, set `SERVER_ADDRESS=0.0.0.0` together with your own private `JWT_SECRET` (never the public dev one), start Vite
with `--host`, and allow ports 8080 and 5173 in the firewall.

Without the profile and without `JWT_SECRET` the app stops at startup with a clear message. For a real run set a secret
instead (generate one, for example, with `[Convert]::ToBase64String((1..48 | % { Get-Random -Max 256 }))`):

```
$env:JWT_SECRET = "<base64 secret, at least 32 bytes decoded>"
$env:ADMIN_PASSWORD = "<strong password>"
mvn spring-boot:run
```

Build a jar:

```
mvn clean package
java -jar target/wallet-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev    # local only; or set JWT_SECRET instead
```

New wallets start at 0. To put money into a wallet, log in as the admin and call `POST /api/admin/wallets/{id}/deposit`
(or use the admin "Deposit to wallet" page of the frontend). For a throwaway local demo you can pre-fund new wallets with
`--app.wallet.initial-balance=100.00`.

The API listens on `http://localhost:8080`.

## Frontend

A React UI lives in `frontend/` (Vite, React Router, plain fetch). With the API running on port 8080:

```
cd frontend
npm install
npm run dev
```

Then open `http://localhost:5173`. The dev server proxies `/api` to the backend. See `frontend/README.md` for details.

## Data model

Created by the Flyway migrations `V1__baseline.sql` and `V2__user_token_version.sql` in `src/main/resources/db/migration` (see [Database schema](#database-schema-flyway)).

**users**: `id`, `username` (unique, also case-insensitively via `uq_users_username_lower`), `email` (unique, also case-insensitively via `uq_users_email_lower`), `password_hash` (BCrypt), `role` (`USER` or `ADMIN`, default `USER`), `enabled` (boolean, default true), `token_version` (BIGINT, default 0; see [Token revocation](#token-revocation)), `created_at`

**wallets**: `id`, `user_id` (unique, FK to users), `balance` (`NUMERIC(19,4)`, check >= 0), `currency`, `version` (optimistic locking)

**transactions**: `id`, `sender_wallet_id`, `receiver_wallet_id` (both FK to wallets and nullable), `amount` (check > 0), `type` (`TRANSFER`, `DEPOSIT`, `WITHDRAWAL`), `status` (`PENDING`, `COMPLETED`, `FAILED`; operations are currently stored as `COMPLETED`), `note` (up to 255 chars, nullable), `created_at`, `idempotency_key` (nullable; unique per owning wallet, see below)

The owner of an idempotency key is the sender wallet of a transfer or withdrawal and the receiver wallet of a deposit; the unique index `uq_tx_owner_idempotency` on `(COALESCE(sender_wallet_id, receiver_wallet_id), idempotency_key)` makes a key unique per owner, so one user can never block or probe another user's keys.

**deposit_requests**: `id`, `wallet_id` and `user_id` (FKs), `amount` (`NUMERIC(19,4)`, check > 0), `note` (up to 255 chars, e.g. a bank transfer reference), `status` (`PENDING`, `APPROVED`, `REJECTED`, checked), `created_at`, `decided_at`, `decided_by` (admin user id), `decision_note`, `transaction_id` (FK to the DEPOSIT transaction created on approval), `idempotency_key` (unique per wallet via `uq_dr_wallet_idempotency`). A check ties the status to the decision fields (an approved request must have a decider, a time and a transaction; a pending one none), and `uq_dr_transaction` makes a transaction linkable to one request only. Indexes on `wallet_id` and `status`. 

A check constraint ties the type to the wallets: a transfer has both wallets, a deposit has only a receiver wallet, a withdrawal has only a sender wallet.


`role` and `enabled` are additions to the data model originally specified.

## Database schema (Flyway)

Flyway owns the schema. Migrations live in `src/main/resources/db/migration` and run at startup, each exactly once; applied versions are recorded in the table `flyway_schema_history`. Hibernate only validates the result (`spring.jpa.hibernate.ddl-auto=validate`). `V1__baseline.sql` is the baseline schema (users, wallets, transactions, deposit_requests with all constraints and indexes); `V2__user_token_version.sql` adds `users.token_version`.

**Changing the schema.** Add a new file `V3__short_description.sql` (next number, two underscores, plain SQL) next to V1 and V2 and update the entities. NEVER edit or rename a migration that has been applied anywhere: Flyway validates checksums at startup and refuses to start if an applied file changed. To fix a mistake, add a new migration.

**Existing databases.** A database created by the previous code (`schema.sql` at every start) has the V1 shape but no `flyway_schema_history`. With `spring.flyway.baseline-on-migrate=true` and `baseline-version=1`, Flyway records it as "already at version 1" and does not run V1 again; later migrations then apply normally. An EMPTY database is never baselined: it runs V1 in full. Because a baseline skips V1, the database must really be at the current shape. On such a database the app first checks the tables, indexes and constraints that V1 defines (`SchemaShapeGuard`) and refuses to start, listing what is missing, if it is older. Supported paths for an older database: first start the previous version of the app once so its `schema.sql` upgrades it to the current shape, or recreate the database. (The same check also runs after every migration, so a dropped index or constraint stops startup.)

**Warning: clean.** Flyway `clean` drops everything in the schema. It is disabled (`spring.flyway.clean-disabled=true`) and must stay disabled; only the integration-test profile enables it, and the tests refuse to run against any database other than `walletdb_test`.

## API

Send `Authorization: Bearer <token>` on every endpoint except `/api/auth/register` and `/api/auth/login`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register a user (role USER) with a wallet; returns a token |
| POST | `/api/auth/login` | public | Log in; returns a token |
| POST | `/api/auth/logout` | any user | Log out of ALL devices: revokes every token of the caller (see [Token revocation](#token-revocation)); returns 204 |
| GET | `/api/wallets/me` | any user | Your wallet |
| GET | `/api/wallets/{id}` | owner or admin | A wallet by id (404 for anyone else, same as a missing wallet) |
| POST | `/api/wallets/me/withdraw` | any user | Withdraw from your wallet (simulated); 422 if funds are insufficient; optional `Idempotency-Key` header |
| POST | `/api/transactions/transfer` | any user | Transfer funds, optional `note`; optional `Idempotency-Key` header |
| GET | `/api/transactions` | any user | Your transactions: paged, sortable, filterable, newest first by default |
| GET | `/api/transactions/{id}` | sender, receiver or admin | A transaction by id (404 for anyone else, same as a missing one) |
| GET | `/api/users/me` | any user | Your profile |
| PATCH | `/api/users/me` | any user | Change your email (validated, must be unique) |
| POST | `/api/users/me/password` | any user | Change your password (verifies the current one); revokes all older tokens and returns 200 with a NEW token |
| GET | `/api/admin/users` | ADMIN | Users, paged and sortable (default sort `id`) |
| PATCH | `/api/admin/users/{id}/status` | ADMIN | Enable or disable a user |
| GET | `/api/admin/transactions` | ADMIN | All transactions; same paging, sorting and filters as `/api/transactions` |
| POST | `/api/admin/wallets/{id}/deposit` | ADMIN | Credit any wallet (simulated); with deposit-request approval one of the two ways money enters the system; optional `Idempotency-Key` header |
| POST | `/api/deposit-requests` | any user | Ask for money to be added to your wallet; 201 with a PENDING request; optional `Idempotency-Key` header; 409 if you already have 5 pending (`app.deposit-requests.max-pending`) |
| GET | `/api/deposit-requests` | any user | Your requests: paged (default 20, max 100), `sort` (`id`, `createdAt`, `amount`, `status`, `decidedAt`; default `createdAt,desc`), filter `status` |
| GET | `/api/deposit-requests/{id}` | owner | One of your requests (404 for anyone else's, same as a missing one) |
| GET | `/api/admin/deposit-requests` | ADMIN | All requests with username; filters `status`, `userId`, `username`; same paging/sorting; without `sort` and `status`, PENDING come first |
| POST | `/api/admin/deposit-requests/{id}/approve` | ADMIN | Approve: credits the wallet exactly once and creates a linked COMPLETED DEPOSIT transaction; optional body `{"decisionNote"}`; 409 if already decided or the user is disabled |
| POST | `/api/admin/deposit-requests/{id}/reject` | ADMIN | Reject (no money moves); body `{"decisionNote"}` is required (max 255); 409 if already decided |

### Deposit requests

Create (same amount rules as every money endpoint: at least 0.0001, up to 15 integer and 4 fraction digits; `note` optional, max 255):

```json
{ "amount": 25.50, "note": "bank transfer ref 20261021-77" }
```

User response (the idempotency key and the deciding admin are never shown to the user):

```json
{ "id": 4, "walletId": 3, "amount": 25.5000, "note": "bank transfer ref 20261021-77", "status": "PENDING",
  "createdAt": "2026-10-21T10:00:00", "decidedAt": null, "decisionNote": null, "transactionId": null }
```

Admin responses additionally contain `userId`, `username` and `decidedBy`. Errors use the usual `{timestamp, status, error}` body: 400 invalid amount/note/key/sort, 401 no token, 403 a USER on `/api/admin/**`, 404 unknown or foreign id, 409 key reused with a different request, pending cap reached, request already APPROVED/REJECTED, or approving a request of a disabled user (it can still be rejected).

After approval the DEPOSIT transaction appears in the user's normal history (note `Deposit request #<id>: <note>`) and the request holds its `transactionId`.

### Paging, sorting and filtering

`GET /api/transactions`, `GET /api/admin/transactions` and `GET /api/admin/users` accept:

- `page` (zero-based, default 0; the row offset `page * size` must fit in an int, otherwise 400 "page is too large"), `size` (default 20, capped at 100), `sort` (`property,asc|desc`, for example `sort=amount,desc`). Sorting is limited to a whitelist, anything else returns 400: users `id`, `username`, `email`, `role`, `enabled`, `createdAt`; transactions `id`, `createdAt`, `amount`, `type`, `status`, `senderWalletId`, `receiverWalletId`.
- Transaction lists also accept the filters `from` and `to` (`yyyy-MM-dd`, both inclusive, between 1900-01-01 and 2200-12-31, otherwise 400), `minAmount`, `maxAmount`, `status` (`PENDING`, `COMPLETED`, `FAILED`) and `type` (`TRANSFER`, `DEPOSIT`, `WITHDRAWAL`).

Responses are page objects:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 4, "totalPages": 1 }
```

Example: `GET /api/transactions?type=DEPOSIT&minAmount=40&sort=amount,asc&size=10`.

### Example bodies

Register (`username` 3-50 chars after trimming, only ASCII letters, digits and `.` `_` `-` (no spaces, look-alike or invisible characters), valid `email`, `password` 8-72 characters and at most 72 bytes in UTF-8) and login. Usernames and emails are trimmed and unique ignoring case (`Alice` and `alice` are the same account); the casing you registered with is kept for display, and login accepts any casing. The admin username and email are reserved and cannot be registered:

```json
{ "username": "alice", "email": "alice@example.com", "password": "password123" }
{ "username": "alice", "password": "password123" }
```

Both return (register returns HTTP 201):

```json
{ "token": "eyJhbGciOi...", "tokenType": "Bearer" }
```

Wallet:

```json
{ "id": 2, "userId": 2, "username": "alice", "balance": 74.5000, "currency": "USD" }
```

Transfer request: give `receiverUsername` or `receiverWalletId` (if both are present, the wallet id is used). `amount` must be at least 0.0001 with at most 4 decimal places.

```json
{ "receiverUsername": "bob", "amount": 25.50, "note": "lunch" }
```

`note` is optional (at most 255 characters). Notes (also deposit-request and decision notes) may not contain control characters (newline, tab, NUL), invisible/format characters (zero-width, soft hyphen, bidi overrides) or line separators; ordinary Unicode text and spaces are fine, otherwise 400. An unknown receiver gives the same 404 "Receiver not found" whether given by username or wallet id.

Deposit and withdrawal request (same body for `/api/wallets/me/withdraw` and `/api/admin/wallets/{id}/deposit`; same amount rules as transfers):

```json
{ "amount": 50 }
```

Transaction response (transfers, deposits and withdrawals all return HTTP 201 and this shape):

```json
{
  "id": 1, "senderWalletId": 2, "receiverWalletId": 3, "amount": 25.5000, "type": "TRANSFER",
  "status": "COMPLETED", "note": "lunch", "createdAt": "2026-10-16T02:15:00.123"
}
```

The `Idempotency-Key` is never echoed back (the other party of a transfer can read the transaction too).

For a deposit, `senderWalletId` is `null`; for a withdrawal, `receiverWalletId` is `null`.

Profile:

```json
GET /api/users/me  ->  { "id": 2, "username": "alice", "email": "alice@example.com", "role": "USER", "enabled": true, "createdAt": "2026-10-16T02:11:13.591" }
PATCH /api/users/me          { "email": "alice2@example.com" }   (409 if the email is taken)
POST /api/users/me/password  { "currentPassword": "password123", "newPassword": "newpassword1" }   (400 if the current password is wrong or the new one is the same; 200 with `{"token": ..., "tokenType": "Bearer"}` on success: use this token from now on, the old one and the tokens of other devices are revoked)
```

Enable or disable a user (admin; returns the updated user, 400 if an admin disables their own account):

```json
PATCH /api/admin/users/2/status   { "enabled": false }
```

A disabled user cannot log in (the same 401 "Invalid username or password" as a wrong password, so a login does not reveal which accounts exist or are disabled) and their existing tokens are rejected (401).

Admin user entry (no password hash is exposed):

```json
{ "id": 1, "username": "admin", "email": "admin@example.com", "role": "ADMIN", "createdAt": "2026-10-16T02:10:17.500" }
```

Errors from the application use this shape:

```json
{ "timestamp": "2026-10-16T00:00:00Z", "status": 422, "error": "Insufficient funds" }
```

Status codes you will see: 400 (validation, transfer to self, no receiver, currency mismatch, invalid `Idempotency-Key` header (blank or over 100 characters), invalid sort property), 401 (bad credentials or disabled account at login, or missing/invalid token; the missing-token 401 has an empty body), 403 (a non-admin calling an admin endpoint), 404 (missing, or not yours: wallets and transactions of other users look exactly like missing ones), 409 (duplicate username/email, idempotency key reused with a different request), 422 (insufficient funds), 429 (rate limit exceeded, see [Rate limiting](#rate-limiting)).

### curl walkthrough

```
# Register two users
curl -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'
curl -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","password":"password123"}'

# Log in as alice and keep the token
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
TOKEN=<paste token here>

# View own wallet
curl localhost:8080/api/wallets/me -H "Authorization: Bearer $TOKEN"

# Transfer 25.50 to bob (repeating this exact call returns the same transaction, no second transfer)
curl -X POST localhost:8080/api/transactions/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: k1" -H "Content-Type: application/json" \
  -d '{"receiverUsername":"bob","amount":25.50}'

# Withdraw (simulated; the wallet needs funds, see the admin deposit below)
curl -X POST localhost:8080/api/wallets/me/withdraw \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"amount":10.25}'

# History (paged, filtered, sorted) and a single transaction
curl "localhost:8080/api/transactions?type=DEPOSIT&minAmount=40&sort=amount,asc&size=10" -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/transactions/1 -H "Authorization: Bearer $TOKEN"

# Profile
curl localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN"
curl -X PATCH localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"email":"alice2@example.com"}'
curl -X POST localhost:8080/api/users/me/password -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"currentPassword":"password123","newPassword":"newpassword1"}'   # returns a new token; the old $TOKEN no longer works
curl -X POST localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"   # 204; revokes all tokens of this user

# Admin (start the app with ADMIN_PASSWORD set, at least 12 characters, then log in as admin)
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ADMIN_PASSWORD>"}'
ADMIN=<paste admin token here>
curl localhost:8080/api/admin/users -H "Authorization: Bearer $ADMIN"
curl "localhost:8080/api/admin/transactions?type=WITHDRAWAL&from=2026-10-01" -H "Authorization: Bearer $ADMIN"
curl -X POST localhost:8080/api/admin/wallets/3/deposit -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" -d '{"amount":20}'
curl -X PATCH localhost:8080/api/admin/users/2/status -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" -d '{"enabled":false}'
```

On Windows PowerShell use `curl.exe` and single-quoted JSON, or `Invoke-RestMethod`.

## Token revocation

Every user has a `token_version` (`users.token_version`, BIGINT, starts at 0). A token carries the version that was current when it was issued in the claim `tv`. `JwtAuthFilter` already loads the user from the database on every request, and compares the claim with the stored value in that same load (no extra query): a token whose `tv` is missing or different is rejected with 401, exactly like an invalid token.

The version is increased with one atomic `UPDATE users SET token_version = token_version + 1` (no read-modify-write, so concurrent bumps are never lost) when:

- the user logs out: `POST /api/auth/logout` (authenticated, returns 204) revokes every token of that user on ALL devices, the one used for the call included;
- the user changes the password: the other sessions die, and the response (200) contains a NEW token for the new version so the current session keeps working; the old token no longer works;
- an admin DISABLES the user (the existing disabled rejection stays; re-enabling does not bump, but the old tokens stay dead, so the user must log in again).

There is no endpoint that changes a role, so there is nothing to bump there yet; a future role change must call `UserRepository.bumpTokenVersion` too. Login and registration issue tokens with the current version. Tokens issued before this feature have no `tv` claim and are rejected: users simply log in again. Logging out is therefore "log out everywhere"; there is no per-device logout. Tokens are still short-lived (1 hour). Details: `TOKEN_REVOCATION_LOG.md`.

## Rate limiting

Brute-force and flood protection with in-memory token buckets (no extra dependency; `RateLimitFilter`, `UserRateLimitFilter`, `TokenBucketLimiter`). Each rule is "N requests per window", refilled continuously, so a blocked client is let in again automatically (5 per minute = a burst of 5, then one every 12 seconds). Nothing is locked permanently. Every attempt counts, successful or not.

| Rule | Applies to | Key | Default |
|---|---|---|---|
| `login-ip` | `POST /api/auth/login` | client IP | 5 per 1 minute |
| `login-username` | `POST /api/auth/login` | target username, trimmed and lower case (unknown names are counted exactly like real ones) | 5 per 1 minute |
| `register-ip` | `POST /api/auth/register` | client IP | 5 per 1 hour |
| `password-user` | `POST /api/users/me/password` | authenticated user | 5 per 15 minutes |
| `password-ip` | `POST /api/users/me/password` | client IP | 5 per 15 minutes |
| `money-user` | `POST /api/transactions/transfer`, `/api/wallets/me/withdraw`, `/api/deposit-requests` (one shared bucket) | authenticated user | 30 per 1 minute |
| `admin-user` | admin deposit, approve and reject | authenticated user | 120 per 1 minute |
| `global-ip` | everything under `/api/**` | client IP | 300 per 1 minute |

Properties (all under `app.rate-limit.`): `enabled` (master switch, default `true`), `<rule>.capacity` and `<rule>.window` for each rule above (for example `login-ip.capacity=5`, `login-ip.window=1m`; durations like `30s`, `15m`, `1h`), `max-keys` (default 50000), `trust-forwarded-headers` (default `false`) and `trusted-proxies` (default 1). The defaults are in `application.properties`.

When a limit is exceeded the API answers **429** with a `Retry-After` header (whole seconds) and the usual error body:

```
HTTP/1.1 429
Retry-After: 12
{"timestamp":"...","status":429,"error":"Too many requests, try again in 12 seconds"}
```

The login and registration limits run first in the security chain, before token parsing or any BCrypt work, so a rejected request is cheap; the per-user limits run right after the JWT filter. A 429 never reveals whether a username exists: the per-username bucket is applied to unknown names in the same way. The frontend shows "Too many attempts. Try again in N seconds." on every form.

**Client address and reverse proxies.** By default the client is the TCP peer address of the connection; `X-Forwarded-For` is ignored, so a client cannot dodge the per-IP limits by sending that header. Behind a reverse proxy or load balancer every request would come from the proxy's address and share one bucket, so set `app.rate-limit.trust-forwarded-headers=true` and `app.rate-limit.trusted-proxies=<number of proxies in front of the app>`. The client is then the entry that many positions from the RIGHT of `X-Forwarded-For` (each trusted proxy appends the address it received the request from; entries to the left can be written by the client and are ignored). If the header is shorter than expected or not an address, the peer address is used. Only enable this when the application is reachable ONLY through those proxies and they set/append the header; otherwise anyone connecting directly can forge it.

**Limitations.** The counters live in this JVM's memory: with several instances each one counts on its own (the effective limit is multiplied) and a restart resets them; a multi-instance deployment needs a shared store such as Redis. Memory is bounded: at most `max-keys` keys per rule (least recently used evicted first, idle keys expire after one window). Users behind one shared address (office, school, mobile carrier NAT) share the per-IP buckets. Because the username bucket is shared across all IPs, someone who keeps guessing a name can keep that account's login slowed to one attempt per 12 seconds (a short throttle, never a lockout; the password stays safe). Details: `RATE_LIMITING_LOG.md`.

## Design notes

- **JWT auth.** Tokens are HMAC-signed with the Base64 secret in `app.jwt.secret`, with the username as subject, the user's token version (`tv`, see [Token revocation](#token-revocation)) plus issued-at and expiry. `JwtAuthFilter` validates the bearer token and loads the user (and current role) from the database on each request. Sessions and CSRF are disabled. Missing or invalid tokens leave the request unauthenticated; protected endpoints then return 401. Authenticated non-admins on `/api/admin/**` get 403. The container's error forward to `/error` is public (dispatcher type ERROR) so error forwards keep their real status; a direct request to `/error` is denied (401/403), never a 500. Multipart is disabled (no uploads), so `multipart/form-data` gets 415. Login compares the password before checking the disabled flag (no timing difference) and treats passwords over 72 bytes as wrong.
- **Transfers** run in one database transaction. Both wallet rows are locked with `SELECT ... FOR UPDATE`, always in ascending wallet id order, so opposite transfers cannot deadlock. Wallets also have a `@Version` column as an optimistic-locking safeguard. The balance check requires `balance >= amount`; the currencies of both wallets must match.
- **Idempotency.** Keys are scoped to the owning wallet (the sender; for a deposit the receiver), so different users can use the same key string independently. If a key was already used by the same owner AND the request is the same (same type, receiver, amount and note for a transfer; same amount for a deposit or withdrawal), the original transaction is returned and nothing is applied. The same key with any difference, or for another operation type, gives 409 "Idempotency key already used with a different request". The header must be 1 to 100 characters (blank or longer gives 400); an absent header means no key. Known limitation: if the same request is sent twice at the same moment, all but one of the simultaneous requests get 409 instead of the original transaction (money still moves exactly once).
- **Deposits and withdrawals** lock the wallet row (`SELECT ... FOR UPDATE`) while the balance changes. A withdrawal requires `balance >= amount` (422 otherwise). Both are simulated; no payment provider is involved.
- **Deposit requests.** Creating a request moves no money. Approval runs in one database transaction: the request row is locked (`SELECT ... FOR UPDATE`) and its status re-checked under the lock, then the wallet row is locked with `findByIdForUpdate`, the balance credited, a COMPLETED DEPOSIT transaction saved and the request set to APPROVED with `transaction_id`. Concurrent or repeated approve/reject calls wait on the row lock and then get 409, so the wallet is credited exactly once (a unique index on `transaction_id` is a last safety net). The lock order (request, then wallet) cannot deadlock with transfers, which lock wallets only. Creating a request locks the caller's own wallet row briefly, which makes the pending cap and the idempotency check race-free per user. See `DEPOSIT_REQUESTS_LOG.md`.
- **Disabled users.** `enabled=false` makes the login fail with the same 401 as a wrong password and makes the JWT filter ignore the user's tokens, so their requests get 401. Disabling also bumps the token version (see [Token revocation](#token-revocation)).
- **Paging.** Handled by Spring Data's `Pageable` argument resolver; the size cap is `spring.data.web.pageable.max-page-size=100`. Transaction filters are built as JPA `Specification`s.
- **Access rules.** A wallet is visible to its owner and admins. A transaction is visible to the owners of its sender and receiver wallets and admins. Anyone else gets 404, the same as for a missing id. Registration always creates role USER.
- **Admin bootstrap.** At startup, if `ADMIN_PASSWORD` (`app.admin.password`) is non-empty (and at least 12 characters, at most 72 bytes) and no user named `app.admin.username` exists, an ADMIN user and wallet are created. An existing account is never modified. The admin username and email are reserved against registration. If the password is too weak, or the username or email is already taken by another account, an error is logged and the admin is not created, but the app still starts.
- **Schema.** Flyway migrations create and change the tables at startup; Hibernate only checks them (`spring.jpa.hibernate.ddl-auto=validate`), so an entity/schema mismatch stops startup.

## Project structure

```
wallet-api
  pom.xml
  src/main/resources
    application.properties
    db/migration/V1__baseline.sql
  src/main/java/com/example/wallet
    WalletApiApplication.java
    controller   AuthController, WalletController, TransactionController, UserController, AdminController,
                 DepositRequestController, AdminDepositRequestController
    service      AuthService, WalletService, TransactionService, DepositRequestService, UserService, AdminBootstrap
    repository   UserRepository, WalletRepository, TransactionRepository, DepositRequestRepository
    entity       User, Wallet, Transaction, DepositRequest, DepositRequestStatus, Role, TransactionStatus, TransactionType
    dto          request/response records
    config       SchemaShapeGuard (Flyway baseline safety check)
    security     SecurityConfig, JwtAuthFilter, JwtService
    exception    ApiException, GlobalExceptionHandler
```

## Status and limitations

- Automated tests: `mvn test` (unit, no database), `mvn verify` (also the integration tests against a separate empty database `walletdb_test`), `cd frontend && npm test`. See `TEST_FINDINGS.md`, `SECURITY_FIXES_LOG.md`.
- Not done yet (see `TEST_FINDINGS.md`): DB credential hardening. Rate limiting is in memory and per instance (see [Rate limiting](#rate-limiting)).
- Defaults are for local development only: the `postgres`/`postgres` database credentials and the plain JDBC URL. There is no default JWT secret.
