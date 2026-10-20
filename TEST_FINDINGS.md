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
