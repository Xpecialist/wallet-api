# Wallet API: test findings (2026-10-20)

Results below are from after the fixes; see `CHANGES_LOG.md` for what was changed and why.
Original failing counts (before fixes): unit 118/119, integration 67/78.

## Status

| Suite | Command | Tests | Pass | Fail |
|---|---|---|---|---|
| Unit (Mockito, no DB) | `mvn test` | 119 | 119 | 0 |
| Integration (real PostgreSQL) | `mvn verify` | 78 | 78 | 0 |

Bugs 1 and 2 below are FIXED (see `CHANGES_LOG.md`). The sections below describe them as originally found. Item 3 was checked: simultaneous same-key replays get 409 for all but one request, money is safe; left as is.

## Fixes applied (reference; branch `Testing`, commit 9eb4d91)

### Fix 1: concurrent wallet requests returned HTTP 500
- Root cause CONFIRMED (reproduced first): `findByUserUsername` left a stale `Wallet` managed in the persistence context; the later `SELECT ... FOR UPDATE` waited for the lock but returned that same stale instance, so the `@Version` check failed at flush.
- Change: `findByIdForUpdate` moved from a `@Query` + `@Lock` on `WalletRepository` into a custom repository fragment (`WalletRepositoryCustom` + `WalletRepositoryCustomImpl`). Same `PESSIMISTIC_WRITE` lock, followed by `em.refresh(wallet)` once the lock is held. `WalletRepository` now extends the fragment. `TransactionService` is unchanged (still locks wallets in ascending id order; unit tests mock these methods, which is why a fragment was used).
- Safety net: `GlobalExceptionHandler` maps `ObjectOptimisticLockingFailureException` to 409 "Concurrent update, please retry". It never triggered in tests.
- Verified: all 9 `ConcurrencyIT` tests pass; live over HTTP, 25 parallel withdrawals of 10 from 100 gave exactly 10x201, 15x422, balance 0, no 5xx.

### Fix 2: default page size was 10 instead of 20
- Cause confirmed: `@PageableDefault` on `AdminController.users`, `AdminController.transactions` and `TransactionController.history` overrides the property.
- Change: `size = 20` added to all three annotations (the only ones in `src/main`). The 100 cap still works.
- NOTE: the 20 now also lives in the annotations; changing `default-page-size` in `application.properties` alone will NOT change the default.
- The three `KNOWN_BUG_defaultPageSizeShouldBe20NotTen_*` tests were renamed `defaultPageSizeIs20_*`; assertions unchanged.

### Live smoke test after the fixes (real HTTP, ~150 checks)
- Run against a second app instance on port 8081 using `walletdb_test` (dev server on 8080 and `walletdb` untouched).
- Result: no bugs, no 5xx, no stack traces. Default page size 20 on `/api/transactions`, `/api/admin/transactions`, `/api/admin/users`; cap 100; sorting, every filter, auth, profile, deposit/withdraw/transfer errors, idempotent sequential replays, admin and enable/disable all matched the README. Concurrency over HTTP (parallel withdrawals, opposing transfers, mixed load, 3-wallet ring) kept balances exact with no deadlock. Password hash never exposed.
- Minor observations, not bugs against the README: a replayed response shows `amount` as `5.0000` and a more precise `createdAt` than the original; framework-generated errors (malformed JSON, Spring Security 403 on admin endpoints) use Spring's default error shape with a `path` field; `size=0`, `size=-1`, `page=-1` and `size=abc` return 200 with defaults instead of 400.
- Not tested: JWT expiry, very large or unicode inputs, extreme connection counts, the frontend UI.

## Bugs as originally found (before the fixes above)

### 1. Concurrent requests on the same wallet return HTTP 500 (serious)
- Parallel requests touching one wallet throw `ObjectOptimisticLockingFailureException: Row was updated or deleted by another transaction ... Wallet#N`. `GlobalExceptionHandler` does not handle it, so the client gets a 500.
- Repro: `ConcurrencyIT.parallelWithdrawalsNeverOverdraw`. 25 parallel withdrawals of 10 from a wallet holding 100. Expected: 10 return 201, 15 return 422, final balance 0, no 5xx. Actual: most return 500. A mixed load of 120 requests gave `{500=100, 201=20}`.
- Money is safe: `mixedParallelLoadKeepsMoneyInvariantsRegardlessOfIndividualRequestOutcome` passes. No negative balance, ledger matches every balance, no deadlock. Users get errors, not wrong balances.
- Suspected cause (NOT confirmed by instrumenting): `TransactionService.transfer`, `withdraw` and `depositToOwn` first load the wallet with `wallets.findByUserUsername(...)`, putting a stale entity (balance + version) in the persistence context. The later `findByIdForUpdate` (`SELECT ... FOR UPDATE`) returns that already-managed stale instance without refreshing it, so the flush fails the version check.
- Possible fixes: load the wallet only through the locking query; or refresh/clear the entity after locking; or retry on / map the optimistic-lock failure.
- Failing tests (8, all in `ConcurrencyIT`): `parallelWithdrawalsNeverOverdraw`, `opposingParallelTransfers...`, `cyclicParallelTransfers...`, `mixedParallelDepositsAndWithdrawals...`, `parallelTransfersFromOneWallet...`, `manyWalletsRandomParallelTransfers...`, `parallelReplayOfSameIdempotencyKey...`, `parallelDepositReplayWithSameKey...`.

### 2. Default page size is 10, not 20
- README (line ~122, "size (default 20, capped at 100)") and `application.properties` (`default-page-size=20`) say 20. Endpoints called without `size` return 10 rows.
- Suspected cause: `@PageableDefault(...)` has its own `size` default of 10 which overrides the property. Used in `AdminController.users` (sort id), `TransactionController.history` and `AdminController.transactions` (sort createdAt DESC).
- Fix: add `size = 20` to each `@PageableDefault`, or drop the annotation and set sorting another way.
- Failing tests (4): unit `ApiWebLayerTest.adminEndpointsAllowedForAdminAndUseDefaultPaging` (line 169); integration `TransactionHistoryIT.KNOWN_BUG_defaultPageSizeShouldBe20NotTen_userHistory`, `_adminTransactions`, `_adminUsers`.

### 3. Possible, not confirmed: idempotency replay under a race
After bug 1 is fixed, `parallelReplayOfSameIdempotencyKey...` may still get 409 instead of the original transaction, which would deviate from the documented replay behaviour. Re-check after fixing bug 1.

## Not covered yet (keep in mind)
- No frontend tests; the React UI and backend were never tested together (end to end).
- No code-review pass (bugs/security) over the backend.
- Integration tests run in-process via MockMvc, not against a real HTTP server.
- The Specification filter unit test uses a mocked JPA criteria API (the integration tests cover real SQL).

## Test setup
- Integration tests are `*IT.java` under `src/test/java/com/example/wallet/it/`; they run only under `mvn verify` (maven-failsafe-plugin, added to `pom.xml`). `mvn test` stays DB-free.
- They use a separate empty database `walletdb_test` on the local PostgreSQL (localhost:5432, postgres/postgres). `TestDbGuard` aborts unless the datasource is exactly `walletdb_test`; `ItBase` truncates the tables before each test. The dev database `walletdb` is never touched.
- Profile file: `src/test/resources/application-it.properties`.
- `walletdb_test` was created with `createdb` from `C:\Program Files\PostgreSQL\16\bin` (not on PATH). On another PC, create an empty `walletdb_test` first.
- Docker/Testcontainers was NOT used (Docker is not installed on the dev PC).
- Unit tests live in `src/test/java/com/example/wallet/` (119): services, JWT, DTO validation, `@WebMvcTest` controllers.
- Test-scope dependencies added to `pom.xml`: `spring-boot-starter-test`, `spring-security-test`, plus the failsafe plugin.

## Other notes
- Dev servers were started from the first PC: backend http://localhost:8080 (Spring Boot, `mvn spring-boot:run`), frontend http://localhost:5173 (Vite, `npm run dev` in `frontend/`). They stop when the session ends.
- `ADMIN_PASSWORD` is unset, so no admin user is bootstrapped; admin endpoints are unusable until it is set.
- Custom agent files were tried and deleted; the built-in `builder` and `tester` agents are used instead.

## Suggested next steps
1. DONE: bugs 1 and 2 fixed; `mvn test` and `mvn verify` green; live smoke test passed.
2. DONE: idempotency race checked (still open, see below).
3. TODO: code review of the backend, then frontend tests and an end-to-end check (UI + backend together).
4. TODO (later): decide on the minor observations above (replay formatting, error shape, paging validation).

## Open items (decided: fix later)
- Simultaneous replays of the same Idempotency-Key return 409 for all but one request instead of the original
  transaction (money is safe). May become a problem for clients that retry in-flight requests; see CHANGES_LOG.md
  ("Item 3"). The React UI has not been checked for this.

## Frontend tests and UI check (2026-10-20)

### Automated frontend tests (Vitest, `cd frontend && npm test`)
- 185 tests (mocked network; no backend or DB needed): 182 pass, 3 fail ON PURPOSE, each labelled `BUG:` (see below).
- Config in `frontend/vitest.config.js` (vite.config.js untouched); tests in `frontend/tests/`.
- Covers: API client (auth header, Idempotency-Key, error mapping, 401 handling), AuthContext and route protection (USER vs ADMIN), format utils, useFetch, money forms (validation, double submit, notes, idempotency keys), transaction browser/pagination/filters, Profile, Wallet lookup, admin pages.

### Frontend bugs (FIXED, see FRONTEND_CHANGES_LOG.md; `BUG:` prefix removed from the tests, 185/185 pass)
1. `BUG: a failing wallet call (404/500) discards the successfully loaded profile` (`tests/auth.test.jsx`). If `/api/users/me` succeeds but `/api/wallets/me` fails with a non-401 error, the app shows "Could not load your profile." and the navbar loses the username and Admin link (`Promise.all` rejects, so neither user nor wallet is set). Location: profile/wallet load effect in `src/auth/AuthContext.jsx`. Fix: load them independently (e.g. `Promise.allSettled`). Most significant of the three.
2. `BUG: a receiver that is only whitespace passes client validation and is sent as an empty username` (`tests/money.test.jsx`). `required` accepts "   " and the code trims only after validation; the request goes out with `receiverUsername: ''` (server answers 400). Location: `submit` in `src/pages/TransferPage.jsx`. Minor.
3. `BUG: null is formatted as 0.00 instead of "-"` (`tests/format.test.js`). `Number(null)` is 0. Location: `src/utils/format.js`. Cosmetic.

### Manual UI check in the browser (test stack: backend 8081 + `walletdb_test`, Vite 5174)
Worked: register, session survives reload, deposit, overdraft rejected with "Insufficient funds" (balance unchanged), transfer with note, history list and counts, wrong-password error, logout, USER redirected away from `/admin/users` with no admin links, admin login, admin users list, disable/enable a user. Browser console showed only the expected 422 and 401 responses.
Not checked manually: phone-width layout, Wallet lookup, Profile, admin deposit and all-transactions views, login by a disabled user.

### Observations (not bugs)
- The History page sends page size 10 itself, while the API default is now 20.
- A retry after a failed request gets a NEW idempotency key, so the server cannot dedupe it; the UI only blocks double submit while a request is in flight. Related to the open simultaneous-replay item above.
- The UI has no client-side self-transfer check; it relies on the server's 400.
- jsdom does not enforce `minlength`; tests check the attributes instead.

### Next steps
- Backend code review; end-to-end check of the remaining UI screens and phone layout.

## Where we stopped (2026-10-20, branch `Testing`, last commit 02cfab5)
All bugs found so far are fixed (2 backend, 3 frontend); `mvn test`, `mvn verify` and `npm test` are green.
Still to do:
- Backend code review (bugs/security that tests would miss).
- Manual UI check of the remaining screens: Profile, Wallet lookup, admin deposit, all-transactions view, phone layout.
- Decide whether to fix the open idempotency item (simultaneous same-key replays return 409).
Backend code review is done (see the section below); next: triage and fix its findings.

## Backend code review (2026-10-21, read-only code-reviewer agent)
Nothing fixed yet. Findings are the reviewer's, confirmed by it in the code but NOT re-verified by me. It did NOT run `mvn dependency:tree`; the dependency/CVE statements come from the Spring Boot 3.3.5 BOM from memory and must be checked with a scanner. Runtime behavior (e.g. the date-filter 500, overflow handling) was not tested. 0 critical, 2 high, 7 medium, 11 low.

### HIGH
- **H1. Built-in default JWT secret allows forging an admin token.** `application.properties:18` `app.jwt.secret=${JWT_SECRET:<committed default>}`; `JwtService` only checks key length. If a deployment forgets `JWT_SECRET`, anyone with the repo can mint an HS256 token with `sub=admin`, get admin access and mint money via `/api/admin/wallets/{id}/deposit`. Fix: remove the default so startup fails without a secret, or fail fast outside a `dev` profile when the known default is in use.
- **H2. Any user can mint unlimited money.** `POST /api/wallets/me/deposit` (`WalletController.deposit` -> `TransactionService.depositToOwn`, ~TS:178) has no payment backing and no cap beyond the max amount per call; `app.wallet.initial-balance=100.00` gives every new account free funds; registration has no throttle (see M1). Fine if the app is a simulation; otherwise gate self-deposit behind a flag/profile (off by default) or remove it, and default the initial balance to 0 outside dev.

### MEDIUM
- **M1. No rate limiting / brute-force protection** on `/api/auth/**` and `/api/users/me/password` (`SecurityConfig`, `AuthController`). Unlimited password guessing and mass registration (feeds H2). Fix: per-IP and per-username throttling (e.g. Bucket4j), optional lockout.
- **M2. Login reveals disabled accounts without a valid password.** `GlobalExceptionHandler.disabled(DisabledException)` returns 403 "Account is disabled" because `DaoAuthenticationProvider` checks enabled before the password; everything else is 401. Fix: map it to the same 401 "Invalid username or password".
- **M3. Idempotency key is global, not per user.** `schema.sql:29` `idempotency_key VARCHAR(100) UNIQUE`; lookups at TS:75, 147, 202. Another user can probe or squat a key (victim's request then gets 409). `TransactionResponse` also returns `idempotencyKey` to the other party (a receiver can read the sender's key via `GET /api/transactions/{id}`). Fix: unique on (wallet id, key) or scope by owner; omit the key from responses to non-owners.
- **M4. Idempotent replay does not compare the payload, and transfer replay does not check the type.** TS:76-81, 148-153, 203-208. Same key with a different amount/receiver/note silently returns the original transaction; `transfer()` only compares `senderWalletId`, so a WITHDRAWAL from the same wallet with that key is returned as a transfer. Fix: store a request fingerprint or compare amount, receiver and type; return 409/422 on mismatch. (Related to the open simultaneous-replay item.)
- **M5. Old dependencies (to be confirmed with a scanner).** `pom.xml:8` Spring Boot 3.3.5 (Oct 2024) is past OSS support. Transitives from the BOM may carry known CVEs (Tomcat 10.1.31, Spring Security 6.3.4 incl. BCrypt/72-byte password handling, Spring Framework 6.1.14, pgjdbc 42.7.4). Fix: upgrade to a supported Boot 3.4/3.5+, run `mvn dependency:tree` and an OWASP/Dependabot scan.
- **M6. `schema.sql` runs on every start with locking DDL.** `spring.sql.init.mode=always` (`application.properties:10`); `schema.sql:36-42` drops and re-adds `chk_tx_type_wallets` each boot (ACCESS EXCLUSIVE lock, full revalidation; concurrent starts can deadlock/fail; may fail on legacy rows). Fix: Flyway/Liquibase or conditional ALTERs.
- **M7. Default DB credentials / no TLS.** `application.properties:4-5` falls back to `postgres/postgres` with a plain `jdbc:` URL. Fix: require env vars outside dev; least-privilege DB user.

### LOW
- **L1.** Usernames/emails are case- and whitespace-sensitive (`schema.sql:3-4`; `existsByUsername/Email`): `Alice` and `alice` can both register, `bob ` with a trailing space is accepted; look-alike impersonation in username-based transfers. Fix: trim + lowercase, unique index on `lower(...)`.
- **L2.** Idempotency-Key header length not validated: >100 chars hits the DB and the blanket `DataIntegrityViolationException` -> 409 mapping masks it (should be 400); it also masks CHECK/overflow errors. Fix: validate length, inspect the constraint name.
- **L3.** Passwords over 72 BYTES are silently truncated by BCrypt (`@Size(max=72)` counts characters, multibyte passwords can exceed 72 bytes); `changePassword` does not reject new == current. Fix: validate UTF-8 byte length.
- **L4.** Tokens not revoked on password change or demotion (claims: sub/iat/exp only, 1h). Disabled users are rejected correctly and the role is re-read each request. Fix: `tokenVersion`/`passwordChangedAt` claim checked in the filter.
- **L5.** Admin bootstrap: no strength check on the admin password; if someone registers `admin` first the bootstrap silently skips, and if the `admin@example.com` email is taken `createUser` throws and ABORTS startup. Fix: reserve the admin username/email, fail with a clear log message.
- **L6.** ID enumeration: `GET /api/wallets/{id}` and `/api/transactions/{id}` return 403 for existing and 404 for missing resources (`WalletService:43-47`, TS:277-285), ids are sequential. Fix: 404 in both cases.
- **L7.** Sort accepts any entity property (`UserService.listAll`; admin only), e.g. `sort=passwordHash` (side channel for hash order); `InvalidDataAccessApiUsageException` mapped to "Invalid sort parameter" can mask real server bugs. Fix: whitelist sort properties.
- **L8.** Date filter: an extreme `to` (near `LocalDate.MAX`) makes `plusDays(1)` throw `DateTimeException` (TS:322) -> likely 500 (NOT tested); `created_at` is `TIMESTAMP` without zone and uses server time. Fix: bound the dates, use `Instant`/`timestamptz`.
- **L9.** Money edge cases: no app-level balance upper bound (repeated large deposits can overflow `NUMERIC(19,4)` and surface as 409 "Data conflict"); deposits/withdrawals don't check or record currency; transfers/deposits to disabled users' wallets allowed; no `CHECK (sender_wallet_id <> receiver_wallet_id)`; no CHECKs on `status`/`type`/`currency`.
- **L10.** History query `(sender = ? OR receiver = ?) ORDER BY created_at` relies on separate single-column indexes; will not scale well. Consider UNION or composite indexes.
- **L11.** Config hardening absent: no explicit CORS config (fine if same-origin/proxy intended, be explicit), no HSTS/HTTPS enforcement, no explicit `server.error.include-*=never` (Boot default already never), no request-size/username length caps beyond Tomcat defaults.

### Info / looks solid
- CSRF disabled is appropriate (stateless bearer API, no cookies). No actuator/H2 console/Swagger in the pom. No logging of secrets/PII found. Registration 409s reveal whether a username/email exists (inherent; pair with M1).
- Solid: DB `CHECK (balance >= 0)` and `amount > 0`; BigDecimal with `@Digits(15,4)` and `@DecimalMin(0.0001)`; balance checked after the pessimistic lock + refresh; ascending lock order; principal-derived `me` endpoints (no IDOR there); owner-or-admin checks; `/api/admin/**` requires ROLE_ADMIN read live from the DB; jjwt signature/expiry/algorithm enforced (`alg=none` rejected), weak key rejected at startup; disabled/deleted users rejected on every request; narrow request DTOs (no mass assignment); BCrypt, no hash in responses; JPA Criteria/parameter binding (no string-built SQL), sort validated against entity properties, page size capped at 100; no stack traces in errors; unknown-user and wrong-password logins both 401 (apart from M2).

### Reviewer could not verify
Exact resolved dependency versions/CVEs; runtime behavior (date-filter 500, overflow surfacing, exception translation); documented intent for self-deposit or the default secret (README/CHANGES_LOG not read closely); frontend and tests were out of scope.

### Suggested next steps
1. Decide intent for H2 (is self-deposit a deliberate simulation?).
2. Fix H1 (remove the default secret), M2 (disabled-login message), and the cheap mediums; re-run `mvn test` and `mvn verify`.
3. Run `mvn dependency:tree` and a vulnerability scan to confirm M5.

## Round 1 security fixes (2026-10-21): see SECURITY_FIXES_LOG.md
User decisions: self-service deposit and the free 100 initial balance were NOT intentional (new accounts start at 0; money enters only through the admin deposit endpoint; a deposit-request-with-admin-approval feature is a LATER task).
Fixed in round 1 (confirmed in code, tests added): H1, H2, M2, M3, M4 (the simultaneous-replay 409 stays OPEN), L1, L2, L3, L5, L6, L7.
Not done: M1, M5, M6, M7, L4, L8-L11. Results: `mvn verify` 159 unit + 108 integration tests pass, frontend 187 tests pass, `npm run build` ok.

## Deposit requests with admin approval (2026-10-21): see DEPOSIT_REQUESTS_LOG.md
The later task from round 1 is done: users request a deposit, an admin approves or rejects; approval credits the wallet exactly once (request row lock + status re-check + wallet lock). `mvn verify` 174 unit + 133 integration tests, frontend 217 tests, `npm run build` ok. Open items and risks are listed in the log.

## Round 2 security fixes (2026-10-23): see SECURITY_FIXES_ROUND2_LOG.md
Attack-pass findings F2-F10 fixed (F9 partly; accepted remainder explained), F11 (npm audit) documented but open because every fix is a major upgrade. Results: `mvn verify` 221 unit + 151 integration tests, frontend 218 tests, `npm run build` ok.

## Dependency upgrades (2026-10-23)
- **M5 fixed: Spring Boot 3.3.5 -> 4.1.2** (Spring Framework 7.0.10, Spring Security 7.1.2, Tomcat 11.0.27, Hibernate 7.4.13, pgjdbc 42.7.14, Jackson 3 via Boot). Staged via 3.5.16 (3.5.x support already ended 2026-06-30; 4.0.x ends 2026-12-31; 4.1.x ends 2027-07-31, per api.spring.io). `mvn verify` green: 221 unit + 151 integration tests. Details and every code change: `SPRING_BOOT_UPGRADE_LOG.md`. NOT verified: no OWASP/NVD scan (CVE status of the new versions unconfirmed), official migration guides were not read (changes found by compiling/testing), `ddl-auto=validate` was only run on `walletdb_test`, not on a real dev `walletdb`. Also note 4.1 support ends 2027-07-31, so plan the next upgrade.
- **F11 fixed: frontend** react-router-dom 7.18.4, vite 8.3.5, @vitejs/plugin-react 6.1.2, vitest 5.0.5. `npm audit` and `npm audit --omit=dev`: 0 vulnerabilities; 218 tests + build green. Needs Node 20.19+ / 22.12+ (vitest 5: 22.12+ or 24+). Details: `FRONTEND_UPGRADE_LOG.md`. A human should click through the routing flows listed there (login/logout redirects, protected and admin routes, history filters with back/forward, refresh on a nested route, admin section) and try the production bundle (Vite 8 uses a new bundler).

## Flyway (M6 fixed, 2026-10-23)
`schema.sql` (re-run on every start) was replaced by Flyway: `V1__baseline.sql` is a CREATE-only baseline proven identical (pg_dump diff) to what the old script built on an empty database; a non-empty database without history is baselined at version 1 after a `SchemaShapeGuard` check (a pre-round-1 database is refused); `clean` is disabled outside the test profile. New schema changes = new numbered `V#__description.sql` files, never edit an applied migration. Verified on the real dev `walletdb`: baselined cleanly, validate OK, data intact. Tests: `mvn verify` 221 unit + 154 integration. Details: `FLYWAY_LOG.md`. Limitation: the guard checks that named objects exist, not their full definitions.
