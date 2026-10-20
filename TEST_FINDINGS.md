# Wallet API: test findings (2026-10-20)

Results below are from after the fixes; see `CHANGES_LOG.md` for what was changed and why.
Original failing counts (before fixes): unit 118/119, integration 67/78.

## Status

| Suite | Command | Tests | Pass | Fail |
|---|---|---|---|---|
| Unit (Mockito, no DB) | `mvn test` | 119 | 119 | 0 |
| Integration (real PostgreSQL) | `mvn verify` | 78 | 78 | 0 |

Bugs 1 and 2 below are FIXED (see `CHANGES_LOG.md`). The sections below describe them as originally found. Item 3 was checked: simultaneous same-key replays get 409 for all but one request, money is safe; left as is.

## Bugs to fix

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
1. Fix bugs 1 and 2 with the builder, then re-run `mvn test` and `mvn verify`.
2. Re-check the idempotency race (item 3).
3. Run a code review of the backend, then add frontend tests and an end-to-end check.

## Open items (decided: fix later)
- Simultaneous replays of the same Idempotency-Key return 409 for all but one request instead of the original
  transaction (money is safe). May become a problem for clients that retry in-flight requests; see CHANGES_LOG.md
  ("Item 3"). The React UI has not been checked for this.
