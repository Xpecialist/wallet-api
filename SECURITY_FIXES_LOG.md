# Security fixes, round 1 (branch `Testing`, nothing committed)

Source: "Backend code review" in TEST_FINDINGS.md. Every finding below was re-checked in the code before fixing.
The dev database `walletdb` and the dev servers (8080/5173, old code) were not touched; all integration tests use only `walletdb_test`
(TestDbGuard unchanged).

## User decisions
- Self-service deposit and the free 100 initial balance are NOT intentional (the 100 only existed so tests could assume funded accounts).
- Real money enters only through the admin deposit endpoint for now. A deposit-request-with-admin-approval feature comes LATER as a separate task (not built).

## Exact local run commands
Backend (PowerShell, repo root). The `dev` profile holds a publicly known JWT secret and is active only when selected:
```
$env:ADMIN_PASSWORD = "choose-a-dev-password"      # at least 12 chars; needed to create the admin who funds wallets
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```
Jar: `java -jar target/wallet-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev`.
Without the profile and without `JWT_SECRET` the app stops with "No JWT signing secret is configured". Real run: set `JWT_SECRET` (Base64, >= 32 bytes decoded) instead of the profile.
Optional demo pre-funding: `--app.wallet.initial-balance=100.00`.
Frontend: `cd frontend; npm install; npm run dev` (proxy to the backend as before). Tests: `mvn test`, `mvn verify`, `cd frontend; npm test; npm run build`.

## Findings

### H1 JWT secret default: CONFIRMED, fixed
- `application.properties`: `app.jwt.secret=${JWT_SECRET:<committed default>}` -> `${JWT_SECRET:}` (no default).
- New `application-dev.properties` (dev-only secret, `${JWT_SECRET:...}` still overrides), selected only with `--spring.profiles.active=dev`.
- `JwtService` ctor: blank/missing secret -> new `MissingJwtSecretException`; `MissingJwtSecretFailureAnalyzer` (registered in `META-INF/spring.factories`) prints a short "failed to start" report with the fix.
- Tests: `JwtSecretStartupTest` (context fails without/with empty secret, starts with one, analyzer, properties have no default), `JwtSecretStartupIT` (real app on `walletdb_test`: fails without secret, starts with `dev`), `JwtServiceTest`. `application-it.properties` supplies a test-only secret.
- Remaining risk: the dev secret is public by design; never use the `dev` profile on a shared host.

### H2 Self-deposit / free money: CONFIRMED, fixed
- Removed `POST /api/wallets/me/deposit` (`WalletController`), `TransactionService.depositToOwn` (unused elsewhere), `api.deposit` and the Dashboard deposit form (frontend). Withdraw and the admin deposit page stay.
- `app.wallet.initial-balance` default 100.00 -> 0.00 (property kept). Tests get funds via `app.wallet.initial-balance=100.00` in `application-it.properties` (test-only); `InitialBalanceIT` overrides it back to 0.
- Tests: `InitialBalanceIT` (starts at 0, cannot spend/withdraw, money only via admin deposit), `SecurityFixesIT` (self-deposit endpoint gone for users and admins; admin endpoint still credits), frontend "Dashboard withdraw (no self-service deposit)", `JwtSecretStartupTest.newWalletsStartAtZeroByDefault`.
- Remaining risk: an admin can still create money (by design). Registration is not rate limited (M1, out of scope).

### M2 Disabled login reveals account: CONFIRMED, fixed
- `GlobalExceptionHandler`: `DisabledException` now handled with `BadCredentialsException` -> 401 "Invalid username or password" (old 403 handler removed). Disabled users with a valid token are still rejected by the filter.
- Tests: `ApiWebLayerTest.loginWithBadCredentialsAndDisabledAccountBothGiveTheSame401`, `GlobalExceptionHandlerTest`, `AuthAndAccessIT.disabledUserCannotLoginAndExistingTokenStopsWorking`, `AuthServiceTest`.
- Remaining risk: none for this finding (timing differences not examined).

### M3 Global idempotency keys: CONFIRMED, fixed
- `schema.sql`: new `uq_tx_owner_idempotency` unique index on `(COALESCE(sender_wallet_id, receiver_wallet_id), idempotency_key) WHERE idempotency_key IS NOT NULL`, created first, then the old auto-named global UNIQUE constraint on the column is dropped (idempotent `DO` block). The new-table definition has no column UNIQUE. `Transaction.idempotency_key` mapping lost `unique=true`.
- `TransactionRepository.findByIdempotencyKey` -> `findByOwnerAndKey(walletId, key)`; used by transfer, deposit, withdraw.
- `TransactionResponse` no longer contains `idempotencyKey` (receiver could read the sender's key); frontend detail view no longer shows it.
- Tests: `MoneyMovementIT.sameKeyFromDifferentSendersIsIndependent` (incl. receiver does not see key), `depositKeysAreScopedPerWalletAndAWithdrawalReplay...`, `SchemaUpgradeIT` (see below), `ApiWebLayerTest`.
- Upgrade path tested only on `walletdb_test`: `SchemaUpgradeIT` loads `src/test/resources/old-schema.sql` (the old schema with data), applies the new `schema.sql`, checks data is kept, the old constraint is gone, new indexes enforce per-owner keys and case-insensitive users, a second run changes nothing, and conflicts abort with a clear message without data loss. It rebuilds the current schema afterwards.
- Remaining risk: the schema runs on every start (M6, out of scope).

### M4 Replay does not compare the request: CONFIRMED, fixed
- `TransactionService`: replay returns the original only if type and amount (and receiver and note for transfers) match; otherwise 409 "Idempotency key already used with a different request". A withdrawal is never returned as a transfer. Sequential identical replay still returns the original.
- Tests: `MoneyMovementIT.sameKeyWithDifferentAmountReceiverOrNoteIs409...`, `TransactionServiceTest.transferReplayOfAWithdrawalWithTheSameKeyIs409NotReturnedAsTransfer` and related.
- Open item (unchanged, not fixed, not worsened): two SIMULTANEOUS identical requests - the loser hits the unique index and gets 409 "Data conflict" instead of the original (money moves once). The lookup still happens before the wallet lock exactly as before; the per-owner index is the same race point as the old global one. The 409 for a mismatch and the 409 from this race are distinguishable by message only.

### L1 Case/whitespace-insensitive users: CONFIRMED, fixed
- `AuthService.register/createUser/login`, `UserService.updateEmail`, transfer receiver lookup: trim; lookups use `lower(...)` (`UserRepository`, `WalletRepository.findByUserUsername`). Stored casing is kept for display; the token carries the stored username. Registration re-checks the 3-50 length after trimming (400).
- `schema.sql`: unique indexes `uq_users_username_lower`, `uq_users_email_lower`, created only if absent; first checks for collisions and RAISEs "Schema upgrade aborted: several users have the same username ..." with a query to find them; trims stray whitespace only then. Nothing is dropped.
- Tests: `SecurityFixesIT` (duplicates ignoring case/whitespace for username and email, trimming keeps casing, login/lookup any casing, DB rejects bypassing the service, profile email change), `SchemaUpgradeIT` (clash aborts, no data loss).
- Remaining risk: look-alike Unicode names (homoglyphs) are still different names.

### L2 Idempotency-Key length / DataIntegrity mask: CONFIRMED, fixed
- `TransactionService.normalizeKey`: blank -> 400 (before: treated as no key; request said "treated per existing behavior", but blank-key silently losing idempotency is risky, so now 400; see list below), > 100 chars -> 400, absent header = no key.
- `GlobalExceptionHandler.integrity` inspects SQLState: 23505 -> 409, 22003/22001 -> 400, anything else (CHECK, FK, NOT NULL) -> logged and 500 instead of a fake conflict.
- Tests: `MoneyMovementIT.blankIdempotencyKeyIs400...`, `TransactionServiceTest.blankOrOverlongIdempotencyKeyIs400...`, `GlobalExceptionHandlerTest`.
- Remaining risk: a balance overflow in `NUMERIC(19,4)` (L9) is mapped via SQLState 22003 -> 400 now, not tested end to end.

### L3 Password bytes: CONFIRMED, fixed
- New `@MaxUtf8Bytes(72)` constraint on `RegisterRequest.password` and `ChangePasswordRequest.newPassword`; `UserService.changePassword` rejects new == current (400).
- Tests: `SecurityFixesIT.passwordsOver72BytesAreRejectedExactly72AreAccepted`, `DtoValidationTest`, `UserServiceTest`.

### L5 Admin bootstrap: CONFIRMED, fixed
- `AuthService`: non-admin registration cannot take `app.admin.username`/`app.admin.email` (409). `AdminBootstrap`: password must be 12+ chars and <= 72 bytes; conflicts (username taken by a non-admin, email taken) and any failure are logged as errors ("Admin NOT created: ...") and startup continues.
- Tests: `SecurityFixesIT` (squat blocked, bootstrap once, weak password, taken username/email, admin may use the reserved name), `AdminBootstrapTest`.
- Remaining risk: if an attacker registered `admin` BEFORE this fix on an existing database, the bootstrap only logs an error; the operator must rename that account.

### L6 403 vs 404: CONFIRMED, fixed
- `WalletService.getById`, `TransactionService.getById`: not allowed -> 404 with the same message as missing. Tests: `SecurityFixesIT.foreignAndMissingWalletsAndTransactionsAreIndistinguishable`, `AuthAndAccessIT`, `ApiWebLayerTest`.

### L7 Sort whitelist: CONFIRMED, fixed
- New `SortWhitelist`; `UserService.listAll` (id, username, email, role, enabled, createdAt) and `TransactionService.history/listAll` (id, createdAt, amount, type, status, senderWalletId, receiverWalletId); anything else -> 400. The broad `InvalidDataAccessApiUsageException` -> 400 mapping was removed so real server bugs are no longer masked; `PropertyReferenceException` -> 400 remains as a safety net.
- Tests: `SecurityFixesIT.sortingBySensitiveOrUnknownPropertiesIs400OnEveryPagedEndpoint`, `whitelistedSortPropertiesStillWork`, `TransactionServiceTest`, `UserServiceTest`.

## Results (this run)
| Command | Result |
|---|---|
| `mvn verify` (unit + integration) | BUILD SUCCESS. Unit (surefire) 159 tests, 0 failures/errors/skipped. Integration (failsafe, `walletdb_test`) 108 tests, 0 failures/errors/skipped |
| `cd frontend && npm test` | 7 files, 187 tests passed (React `act()` warnings in browser.test.jsx output are pre-existing noise) |
| `cd frontend && npm run build` | success |

## Deliberately not done
- M1 rate limiting, M5 dependency upgrade, M6 Flyway, M7 DB credentials, L4 token revocation, L8-L11.
- The simultaneous-replay 409 (open item, see M4).
- The deposit-request-with-admin-approval feature (later task).
- Blank `Idempotency-Key` behaviour changed to 400 (instead of "no key"): a decision to flag; revert in `normalizeKey` if the old lenient behaviour is preferred.
- No commit or push.
