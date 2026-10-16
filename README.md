# wallet-api

A simple digital wallet REST API built with Spring Boot 3.3, Java 17, Maven and PostgreSQL.

## Features

- User registration and login with JWT authentication (Spring Security, stateless)
- Each user has exactly one wallet; money is stored as `BigDecimal` (`NUMERIC(19,4)`)
- Transfer funds to another user by username or by wallet id
- View your own wallet, or a specific wallet by id (owner or admin)
- View your transaction history, or a specific transaction by id (participants or admin)
- Simulated deposits and withdrawals (no payment provider); admins can credit any wallet
- Optional note on transfers
- Paginated, sortable, filterable transaction history and admin lists
- Profile management (view, change email, change password); admins can disable and enable users
- Admin endpoints to list all users and all transactions
- Optional idempotency keys on transfers, deposits and withdrawals

## Prerequisites

- JDK 17
- Maven 3.9+
- PostgreSQL 16 (any recent version should work) listening on `localhost:5432`

## Setup

1. Create the database (the tables are created by the app on startup):

   ```
   psql -U postgres -h localhost -c "CREATE DATABASE walletdb"
   ```

2. Optionally set environment variables. All have defaults suitable for local development only.

| Variable / property | Default | Purpose |
|---|---|---|
| `DB_USERNAME` | `postgres` | Database user (`spring.datasource.username`) |
| `DB_PASSWORD` | `postgres` | Database password (`spring.datasource.password`) |
| `JWT_SECRET` | built-in dev secret | Base64-encoded HMAC secret (`app.jwt.secret`); must decode to at least 32 bytes |
| `ADMIN_PASSWORD` | empty | Password for the bootstrap admin (`app.admin.password`). If empty, no admin is created |
| `app.jwt.expiration-ms` | `3600000` (1 hour) | Token lifetime |
| `app.wallet.initial-balance` | `100.00` | Balance given to every new wallet |
| `app.wallet.currency` | `USD` | Currency of every new wallet |
| `app.admin.username` | `admin` | Username of the bootstrap admin |
| `app.admin.email` | `admin@example.com` | Email of the bootstrap admin |

The JDBC URL is `jdbc:postgresql://localhost:5432/walletdb` (edit `src/main/resources/application.properties` to change it). The `app.*` properties not backed by an environment variable can be overridden the usual Spring way, for example `--app.wallet.initial-balance=0` or the `APP_WALLET_INITIAL_BALANCE` environment variable.

## Run and build

PowerShell:

```
$env:ADMIN_PASSWORD = "choose-a-dev-password"
mvn spring-boot:run
```

Build a jar:

```
mvn clean package
java -jar target/wallet-api-0.0.1-SNAPSHOT.jar
```

The API listens on `http://localhost:8080`.

## Data model

Defined in `src/main/resources/schema.sql`, which runs on every startup. It is idempotent: tables are `CREATE TABLE IF NOT EXISTS`, and the `ALTER TABLE` statements at the end upgrade a database created by an earlier version without dropping data.

**users**: `id`, `username` (unique), `email` (unique), `password_hash` (BCrypt), `role` (`USER` or `ADMIN`, default `USER`), `enabled` (boolean, default true), `created_at`

**wallets**: `id`, `user_id` (unique, FK to users), `balance` (`NUMERIC(19,4)`, check >= 0), `currency`, `version` (optimistic locking)

**transactions**: `id`, `sender_wallet_id`, `receiver_wallet_id` (both FK to wallets and nullable), `amount` (check > 0), `type` (`TRANSFER`, `DEPOSIT`, `WITHDRAWAL`), `status` (`PENDING`, `COMPLETED`, `FAILED`; operations are currently stored as `COMPLETED`), `note` (up to 255 chars, nullable), `created_at`, `idempotency_key` (unique, nullable)

A check constraint ties the type to the wallets: a transfer has both wallets, a deposit has only a receiver wallet, a withdrawal has only a sender wallet.

Schema changes in this version (applied automatically to an existing database on startup): `users.enabled` added; `transactions.type` added (existing rows become `TRANSFER`); `transactions.note` added; `NOT NULL` dropped from `sender_wallet_id` and `receiver_wallet_id`; check constraint `chk_tx_type_wallets` and an index on `created_at` added.

`role` and `enabled` are additions to the data model originally specified.

## API

Send `Authorization: Bearer <token>` on every endpoint except `/api/auth/**`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register a user (role USER) with a wallet; returns a token |
| POST | `/api/auth/login` | public | Log in; returns a token |
| GET | `/api/wallets/me` | any user | Your wallet |
| GET | `/api/wallets/{id}` | owner or admin | A wallet by id |
| POST | `/api/wallets/me/deposit` | any user | Top up your own wallet (simulated); optional `Idempotency-Key` header |
| POST | `/api/wallets/me/withdraw` | any user | Withdraw from your wallet (simulated); 422 if funds are insufficient; optional `Idempotency-Key` header |
| POST | `/api/transactions/transfer` | any user | Transfer funds, optional `note`; optional `Idempotency-Key` header |
| GET | `/api/transactions` | any user | Your transactions: paged, sortable, filterable, newest first by default |
| GET | `/api/transactions/{id}` | sender, receiver or admin | A transaction by id |
| GET | `/api/users/me` | any user | Your profile |
| PATCH | `/api/users/me` | any user | Change your email (validated, must be unique) |
| POST | `/api/users/me/password` | any user | Change your password (verifies the current one); returns 204 |
| GET | `/api/admin/users` | ADMIN | Users, paged and sortable (default sort `id`) |
| PATCH | `/api/admin/users/{id}/status` | ADMIN | Enable or disable a user |
| GET | `/api/admin/transactions` | ADMIN | All transactions; same paging, sorting and filters as `/api/transactions` |
| POST | `/api/admin/wallets/{id}/deposit` | ADMIN | Credit any wallet (simulated); optional `Idempotency-Key` header |

### Paging, sorting and filtering

`GET /api/transactions`, `GET /api/admin/transactions` and `GET /api/admin/users` accept:

- `page` (zero-based, default 0), `size` (default 20, capped at 100), `sort` (`property,asc|desc`, for example `sort=amount,desc`). An unknown sort property returns 400.
- Transaction lists also accept the filters `from` and `to` (`yyyy-MM-dd`, both inclusive), `minAmount`, `maxAmount`, `status` (`PENDING`, `COMPLETED`, `FAILED`) and `type` (`TRANSFER`, `DEPOSIT`, `WITHDRAWAL`).

Responses are page objects:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 4, "totalPages": 1 }
```

Example: `GET /api/transactions?type=DEPOSIT&minAmount=40&sort=amount,asc&size=10`.

### Example bodies

Register (`username` 3-50 chars, valid `email`, `password` 8-72 chars) and login:

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

`note` is optional (at most 255 characters).

Deposit and withdrawal request (same body for `/api/wallets/me/deposit`, `/api/wallets/me/withdraw` and `/api/admin/wallets/{id}/deposit`; same amount rules as transfers):

```json
{ "amount": 50 }
```

Transaction response (transfers, deposits and withdrawals all return HTTP 201 and this shape):

```json
{
  "id": 1, "senderWalletId": 2, "receiverWalletId": 3, "amount": 25.5000, "type": "TRANSFER",
  "status": "COMPLETED", "note": "lunch", "createdAt": "2026-10-16T02:15:00.123", "idempotencyKey": "k1"
}
```

For a deposit, `senderWalletId` is `null`; for a withdrawal, `receiverWalletId` is `null`.

Profile:

```json
GET /api/users/me  ->  { "id": 2, "username": "alice", "email": "alice@example.com", "role": "USER", "enabled": true, "createdAt": "2026-10-16T02:11:13.591" }
PATCH /api/users/me          { "email": "alice2@example.com" }   (409 if the email is taken)
POST /api/users/me/password  { "currentPassword": "password123", "newPassword": "newpassword1" }   (400 if the current password is wrong; 204 on success)
```

Enable or disable a user (admin; returns the updated user, 400 if an admin disables their own account):

```json
PATCH /api/admin/users/2/status   { "enabled": false }
```

A disabled user cannot log in (403 "Account is disabled") and their existing tokens are rejected (401).

Admin user entry (no password hash is exposed):

```json
{ "id": 1, "username": "admin", "email": "admin@example.com", "role": "ADMIN", "createdAt": "2026-10-16T02:10:17.500" }
```

Errors from the application use this shape:

```json
{ "timestamp": "2026-10-16T00:00:00Z", "status": 422, "error": "Insufficient funds" }
```

Status codes you will see: 400 (validation, transfer to self, no receiver, currency mismatch), 401 (bad credentials, or missing/invalid token; the missing-token 401 has an empty body), 403 (not allowed), 404, 409 (duplicate username/email, idempotency key used by another sender), 422 (insufficient funds).

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

# Deposit and withdraw (simulated)
curl -X POST localhost:8080/api/wallets/me/deposit \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: d1" -H "Content-Type: application/json" -d '{"amount":50}'
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
  -d '{"currentPassword":"password123","newPassword":"newpassword1"}'

# Admin (start the app with ADMIN_PASSWORD set, then log in as admin)
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

## Design notes

- **JWT auth.** Tokens are HMAC-signed with the Base64 secret in `app.jwt.secret`, with the username as subject plus issued-at and expiry. `JwtAuthFilter` validates the bearer token and loads the user (and current role) from the database on each request. Sessions and CSRF are disabled. Missing or invalid tokens leave the request unauthenticated; protected endpoints then return 401. Authenticated non-admins on `/api/admin/**` get 403. `/error` is public so error forwards keep their real status.
- **Transfers** run in one database transaction. Both wallet rows are locked with `SELECT ... FOR UPDATE`, always in ascending wallet id order, so opposite transfers cannot deadlock. Wallets also have a `@Version` column as an optimistic-locking safeguard. The balance check requires `balance >= amount`; the currencies of both wallets must match.
- **Idempotency.** If an `Idempotency-Key` header is sent and a transaction with that key exists from the same sender, that transaction is returned and nothing is transferred. A key used by a different sender gives 409. The column is unique. Deposits and withdrawals work the same way: a repeated key for the same wallet and operation returns the original transaction; a key used for a different wallet or operation gives 409.
- **Deposits and withdrawals** lock the wallet row (`SELECT ... FOR UPDATE`) while the balance changes. A withdrawal requires `balance >= amount` (422 otherwise). Both are simulated; no payment provider is involved.
- **Disabled users.** `enabled=false` makes the login fail with 403 and makes the JWT filter ignore the user's tokens, so their requests get 401.
- **Paging.** Handled by Spring Data's `Pageable` argument resolver; the size cap is `spring.data.web.pageable.max-page-size=100`. Transaction filters are built as JPA `Specification`s.
- **Access rules.** A wallet is visible to its owner and admins. A transaction is visible to the owners of its sender and receiver wallets and admins. Registration always creates role USER.
- **Admin bootstrap.** At startup, if `ADMIN_PASSWORD` (`app.admin.password`) is non-empty and no user named `app.admin.username` exists, an ADMIN user and wallet are created. An existing account is never modified.
- **Schema.** `schema.sql` creates the tables at startup (`spring.sql.init.mode=always`); Hibernate only checks them (`spring.jpa.hibernate.ddl-auto=validate`), so an entity/schema mismatch stops startup.

## Project structure

```
wallet-api
  pom.xml
  src/main/resources
    application.properties
    schema.sql
  src/main/java/com/example/wallet
    WalletApiApplication.java
    controller   AuthController, WalletController, TransactionController, UserController, AdminController
    service      AuthService, WalletService, TransactionService, UserService, AdminBootstrap
    repository   UserRepository, WalletRepository, TransactionRepository
    entity       User, Wallet, Transaction, Role, TransactionStatus, TransactionType
    dto          request/response records
    security     SecurityConfig, JwtAuthFilter, JwtService
    exception    ApiException, GlobalExceptionHandler
```

## Status and limitations

- There are no automated tests yet.
- The code has not yet been reviewed; it was only smoke-tested manually.
- Defaults are for local development only: the JWT secret in `application.properties`, the `postgres`/`postgres` database credentials, and the 100.00 starting balance for new wallets (set `app.wallet.initial-balance=0` for real use).
