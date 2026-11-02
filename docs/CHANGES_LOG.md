# Change log: fixes for the bugs in TEST_FINDINGS.md (2026-10-20)

Branch `Testing`. Nothing committed. Dev database `walletdb` and the dev servers (8080/5173, old code) were not touched.

## Bug 1: concurrent requests on one wallet returned HTTP 500

**Root cause: confirmed.** Reproduced first (`ConcurrencyIT.parallelWithdrawalsNeverOverdraw` failed with
`ObjectOptimisticLockingFailureException ... Wallet#1`). `transfer`, `withdraw` and `depositToOwn` first load the wallet
with `wallets.findByUserUsername(...)`, so a (possibly stale) `Wallet` is managed in the persistence context. The later
`findByIdForUpdate` (`SELECT ... FOR UPDATE`) waited for the lock but then returned that same managed instance without
reloading it, so the old balance/version was used and the flush failed the `@Version` check. The fix below removes
exactly that behaviour and all concurrency tests pass, which confirms the cause.

**Files / methods**
- `repository/WalletRepositoryCustom.java` (new) and `repository/WalletRepositoryCustomImpl.java` (new):
  `findByIdForUpdate(Long)`. Before: a `@Lock(PESSIMISTIC_WRITE)` `@Query` on `WalletRepository`. After: same query with
  the same lock via `EntityManager`, then `em.refresh(wallet)` once the lock is held, so balance and version are
  current even when the wallet was loaded earlier in the transaction.
- `repository/WalletRepository.java`: removed the `findByIdForUpdate` query (and its unused imports), now extends
  `WalletRepositoryCustom`. Method name and signature are unchanged, so `TransactionService` and its unit tests
  (which mock `findByUserUsername` + `findByIdForUpdate`) needed no change.
- `exception/GlobalExceptionHandler.java`: new `optimisticLock(...)` handler maps
  `ObjectOptimisticLockingFailureException` to 409 `"Concurrent update, please retry"` instead of 500. Safety net only;
  it no longer occurs in the tests.
- `TransactionService` is unchanged: ascending-id lock order for transfers is kept (no deadlocks).

**Tests now passing:** all 8 `ConcurrencyIT` tests that failed (`parallelWithdrawalsNeverOverdraw`: 10x201, 15x422,
balance 0, no 5xx; opposing/cyclic/one-wallet/many-wallet transfers; mixed deposits+withdrawals; both idempotency
replay tests) plus the 9th, which already passed.

**Remaining risk**
- One extra `SELECT` per locked wallet (the refresh). Negligible here.
- A client still can get 409 on a rare optimistic-lock failure (should not happen with the row locks held).

### Item 3: idempotency replay under a race (checked, not changed)
After bug 1 is fixed, 10 parallel transfers with the same `Idempotency-Key` give statuses
`[409 x9, 201 x1]` (observed with a temporary probe, since removed). Money moves exactly once (1 transaction, alice 90,
bob 110) and there are no 5xx. The losers get 409 "Data conflict" (unique-key violation on insert, because the
idempotency lookup happens before the wallet lock) rather than the original transaction with 201. Sequential replays
return the original transaction as documented. README does not describe simultaneous replays and the tests accept 409
for this case, so this is NOT a clear contradiction; not fixed. Possible later improvement: do the idempotency
lookup again after taking the wallet lock, or catch the unique violation and return the existing row.

**OPEN / MAY BE A PROBLEM LATER (decision: keep in mind, fix in a later round).** A client that retries a request
(timeout, double click, flaky network) while the first attempt is still in flight gets a 409 instead of the original
result. The money is safe (it moves exactly once), but the client cannot tell whether its transfer succeeded and may
show an error or retry in a loop. The React UI has not been checked for this. The tests currently accept 409 here
(`parallelReplayOfSameIdempotencyKey...`, `parallelDepositReplayWithSameKey...`); tighten them to expect the original
transaction once this is fixed.

## Bug 2: default page size was 10 instead of 20

**Root cause: confirmed.** `@PageableDefault` has its own `size` default of 10, which overrides
`spring.data.web.pageable.default-page-size=20`.

**Files / methods** (added `size = 20` to each annotation; sort unchanged)
- `controller/AdminController.java`: `users` and `transactions`.
- `controller/TransactionController.java`: `history`.
- No other `@PageableDefault` or `Pageable` list endpoints exist in `src/main`.
- The 100 cap (`max-page-size`) is unaffected; its existing tests still pass.

**Tests now passing:** unit `ApiWebLayerTest.adminEndpointsAllowedForAdminAndUseDefaultPaging`; integration
`TransactionHistoryIT` three default-size tests. Relabeled (only change to tests; assertions untouched):
`KNOWN_BUG_defaultPageSizeShouldBe20NotTen_userHistory/_adminTransactions/_adminUsers` ->
`defaultPageSizeIs20_userHistory/_adminTransactions/_adminUsers`, and their Javadoc "expected to fail" wording.
(`src/test/java/com/example/wallet/it/TransactionHistoryIT.java`.)

**Remaining risk:** the 20 is now written in three annotations as well as in `application.properties`; changing the
property alone will not change the default.

## Test results

| Suite | Command | Tests | Pass | Fail |
|---|---|---|---|---|
| Unit (Mockito, no DB) | `mvn test` | 119 | 119 | 0 |
| Integration (PostgreSQL `walletdb_test`) | `mvn verify` | 78 | 78 | 0 |

(`mvn verify` also runs the 119 unit tests; BUILD SUCCESS.)
