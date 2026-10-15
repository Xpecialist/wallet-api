# wallet-api

A simple digital wallet REST API built with Spring Boot 3.3, Java 17, Maven and PostgreSQL.

## Features

- User registration and login with JWT authentication (Spring Security, stateless)
- Each user has exactly one wallet; money is stored as `BigDecimal` (`NUMERIC(19,4)`)
- Transfer funds to another user by username or by wallet id
- View your own wallet, or a specific wallet by id (owner or admin)
- View your transaction history, or a specific transaction by id (participants or admin)
- Admin endpoints to list all users and all transactions
- Optional idempotency keys on transfers

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

Defined in `src/main/resources/schema.sql` (run on every startup, all statements are `IF NOT EXISTS`).

**users**: `id`, `username` (unique), `email` (unique), `password_hash` (BCrypt), `role` (`USER` or `ADMIN`, default `USER`), `created_at`

**wallets**: `id`, `user_id` (unique, FK to users), `balance` (`NUMERIC(19,4)`, check >= 0), `currency`, `version` (optimistic locking)

**transactions**: `id`, `sender_wallet_id`, `receiver_wallet_id` (both FK to wallets), `amount` (check > 0), `status` (`PENDING`, `COMPLETED`, `FAILED`; transfers are currently stored as `COMPLETED`), `created_at`, `idempotency_key` (unique, nullable)

`role` is an addition to the data model you specified; it is needed to tell admins from regular users.

## API

Send `Authorization: Bearer <token>` on every endpoint except `/api/auth/**`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register a user (role USER) with a wallet; returns a token |
| POST | `/api/auth/login` | public | Log in; returns a token |
| GET | `/api/wallets/me` | any user | Your wallet |
| GET | `/api/wallets/{id}` | owner or admin | A wallet by id |
| POST | `/api/transactions/transfer` | any user | Transfer funds; optional `Idempotency-Key` header |
| GET | `/api/transactions` | any user | Your transactions, newest first |
| GET | `/api/transactions/{id}` | sender, receiver or admin | A transaction by id |
| GET | `/api/admin/users` | ADMIN | All users |
| GET | `/api/admin/transactions` | ADMIN | All transactions |

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
{ "receiverUsername": "bob", "amount": 25.50 }
```

Transaction response (transfer returns HTTP 201):

```json
{
  "id": 1, "senderWalletId": 2, "receiverWalletId": 3, "amount": 25.5000,
  "status": "COMPLETED", "createdAt": "2026-10-16T02:15:00.123", "idempotencyKey": "k1"
}
```

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

# History and a single transaction
curl localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/transactions/1 -H "Authorization: Bearer $TOKEN"

# Admin (start the app with ADMIN_PASSWORD set, then log in as admin)
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ADMIN_PASSWORD>"}'
ADMIN=<paste admin token here>
curl localhost:8080/api/admin/users -H "Authorization: Bearer $ADMIN"
curl localhost:8080/api/admin/transactions -H "Authorization: Bearer $ADMIN"
```

On Windows PowerShell use `curl.exe` and single-quoted JSON, or `Invoke-RestMethod`.

## Design notes

- **JWT auth.** Tokens are HMAC-signed with the Base64 secret in `app.jwt.secret`, with the username as subject plus issued-at and expiry. `JwtAuthFilter` validates the bearer token and loads the user (and current role) from the database on each request. Sessions and CSRF are disabled. Missing or invalid tokens leave the request unauthenticated; protected endpoints then return 401. Authenticated non-admins on `/api/admin/**` get 403. `/error` is public so error forwards keep their real status.
- **Transfers** run in one database transaction. Both wallet rows are locked with `SELECT ... FOR UPDATE`, always in ascending wallet id order, so opposite transfers cannot deadlock. Wallets also have a `@Version` column as an optimistic-locking safeguard. The balance check requires `balance >= amount`; the currencies of both wallets must match.
- **Idempotency.** If an `Idempotency-Key` header is sent and a transaction with that key exists from the same sender, that transaction is returned and nothing is transferred. A key used by a different sender gives 409. The column is unique.
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
    controller   AuthController, WalletController, TransactionController, AdminController
    service      AuthService, WalletService, TransactionService, AdminBootstrap
    repository   UserRepository, WalletRepository, TransactionRepository
    entity       User, Wallet, Transaction, Role, TransactionStatus
    dto          request/response records
    security     SecurityConfig, JwtAuthFilter, JwtService
    exception    ApiException, GlobalExceptionHandler
```

## Status and limitations

- There are no automated tests yet.
- The code has not yet been reviewed; it was only smoke-tested manually (register, login, transfer, admin access).
- Defaults are for local development only: the JWT secret in `application.properties`, the `postgres`/`postgres` database credentials, and the 100.00 starting balance for new wallets (set `app.wallet.initial-balance=0` for real use).
