# Simultaneous Idempotency-Key replay: fix log

Date: 2026-10-29, branch `Testing`. Not committed. All DB work on `walletdb_test` only.

## 1. Root cause (reproduced first)
`TransactionService.transfer / withdraw / deposit` looked the key up BEFORE taking the wallet row lock. Two simultaneous
requests both saw "no such key", both took the lock one after the other, and the second one hit the per-owner unique index
`uq_tx_owner_idempotency` on insert. `GlobalExceptionHandler` mapped that SQLState 23505 to 409 "Data conflict".
Money moved once (the second insert and its balance update rolled back), but the client got 409 instead of the original.

Reproduction: I first changed `ConcurrencyIT.parallelReplayOfSameIdempotencyKeyAppliesTheTransferExactlyOnce` and
`parallelDepositReplayWithSameKeyCreditsOnce` to require 201 for every request with an identical body, and ran them against
the UNMODIFIED service code. Both failed, e.g. statuses `[409, 201, 201, 409, 201, 201, 409, 201, 201, 201]` (transfer) and
`[201, 201, 201, 409, 201, 201, 201, 201, 409, 201]` (deposit).

Deposit-request creation did NOT have the race: `DepositRequestService.create` already locks the wallet row first and checks
the key under that lock (its parallel test already got 201 for all; I only made it stricter, see tests).

## 2. Design chosen: lock-then-check, plus a retry safety net
* Lock-then-check (primary): the key is now looked up after the owner's wallet row is locked, inside the same transaction.
  Every writer of a keyed row holds the owner's lock (transfer: sender, withdraw: own wallet, admin deposit: target wallet,
  deposit request: own wallet), so requests with the same owner and key are serialised. The second one waits, then (READ
  COMMITTED, fresh query) sees the committed row, compares the payload and returns it, or 409 on a mismatch. No exception is
  involved, no poisoned session, and the existing lock order is untouched (transfer still locks ascending ids; the check
  only moved after the locks).
* Why not catch-and-fetch as the main mechanism: it needs a second transaction after a failed insert (the session is
  poisoned), relies on exceptions for normal flow and leaves a window where the replay is answered from a different snapshot.
  Lock-then-check costs one extra lock wait only for replays, which are rare.
* Safety net: new `IdempotentRetry.run(...)`, called by the four controllers outside the service transaction. If the whole
  operation fails with SQLState 23505 on an `*_idempotency` index, it is run exactly once more through the transactional
  proxy, i.e. in a new transaction and new Hibernate session. The failed attempt was rolled back completely (balances
  included), so the retry cannot double-apply money; it finds the committed row and returns it, or 409 on a mismatch. Other
  23505 violations (username/email), other SQLStates, ApiExceptions and a second failure are NOT retried and behave as before.
  With the primary fix the net should never fire; there is no honest way to trigger it end to end, so it is covered by unit tests only.

## 3. Code changes
* `service/TransactionService.java`: the three inline lookups (`if (key != null) { findByOwnerAndKey ... }` before the locks)
  were removed and replaced by a private `replayOrNull(ownerId, key, samePayload)` called right after the lock(s):
  transfer after both wallets are locked and `sender`/`receiver` resolved, and BEFORE the currency and balance checks (a replay
  must still return the original when the balance no longer covers it); withdraw/deposit right after `findByIdForUpdate`.
  Payload predicates are the same as before (type, receiver, amount, note for transfer; type and amount otherwise). Javadoc updated.
  Unknown receiver / unknown wallet still give 404 as before (a replay for a non-existent wallet cannot exist).
* `service/IdempotentRetry.java` (new): the safety net described above.
* Controllers `TransactionController` (transfer), `WalletController` (withdraw), `AdminController` (deposit),
  `DepositRequestController` (create): `service.x(...)` became `IdempotentRetry.run(() -> service.x(...))`.
* `entity/Transaction.java`, `entity/DepositRequest.java`:
  1. `setAmount` stores the value with scale 4 (HALF_UP, the column is NUMERIC(19,4), request validation allows at most 4
     decimals). Before, the first response showed `"amount":10` (request scale) while every later replay showed
     `10.0000` (read back from the DB), so a replay was not byte-identical to the first answer. Visible API change: the
     amount of a freshly created transaction/request is now `10.0000` instead of `10` in the 201 body (same number; it already was `10.0000` in every GET and replay).
  2. `@PrePersist` truncates `createdAt` to microseconds (PostgreSQL precision), for the same reason (first response had nanoseconds).
* `exception/GlobalExceptionHandler.java`: comment only. The 409 text for a different payload was already
  "Idempotency key already used with a different request" (M4), unchanged; a bare unique violation still maps to "Data conflict" (409).
* `README.md`: the "known limitation" sentence in the Idempotency bullet replaced by the new behavior.

## 4. Behavior matrix
| Case | Result |
|---|---|
| same owner, same key, same payload, sequential | 201, same body as the first call (unchanged; now also identical in amount scale) |
| same owner, same key, same payload, simultaneous (N=40) | every response 201 with the identical body, one row, money moved once |
| same key, different payload (amount, receiver, note, type; request amount/note) | 409 "Idempotency key already used with a different request"; simultaneous: exactly one payload wins, the others get 409 (or the original if identical) |
| different owners, same key | independent, each gets its own transaction |
| replay after the balance can no longer cover it | still 201 original (not 422) |
| keyed request that failed (e.g. 422) | creates no row; the key stays free for a later valid retry |
| new key with the same payload | a genuine new request (e.g. 422 if funds are insufficient), never mistaken for a replay |
| duplicate username/email or other unique violation | unchanged: 409 "Data conflict", not retried |

## 5. Tests
Changed (now REQUIRE the original): `ConcurrencyIT.parallelReplayOfSameIdempotencyKeyAppliesTheTransferExactlyOnce`,
`ConcurrencyIT.parallelDepositReplayWithSameKeyCreditsOnce`, and the keyed part of
`DepositRequestConcurrencyIT.parallelCreateRequestsNeverExceedThePendingCapAndReplayCreatesOne`: all 201 and one distinct
body (before: 201 or 409 accepted). Nothing weakened.
`TransactionServiceTest.transferReplayWithDifferentAmountReceiverOrNoteIs409AndMovesNothing`: one added stub (`findByIdForUpdate(3L)`
for carol), because the key is now checked after the lock and the test needs the lock query to find carol.
`ItBase`: new helpers `runParallelResults`, `statusesOf`, `distinctBodies` (full responses of parallel calls).

New `IdempotencyReplayIT` (16 tests, N=40 parallel): identical transfer / withdraw / admin deposit / deposit-request create
(all 201, same body, same id, one row, balances changed once); same key with different amounts for transfer, withdraw, deposit;
different receivers; different deposit-request payloads; different operation types (withdraw vs deposit); sequential replay
and mismatch; replay after the balance is gone (+ new key gives 422); failed keyed request leaves key free; different owners
with a shared key in parallel; opposite-direction transfers mixed with keyed replays (no deadlock, 42 rows, sum conserved);
randomized mixed run (25 operations x 1-4 copies of transfer/withdraw/deposit, 5 users, tight balances): statuses only 201/422,
each key at most one transaction id, row count = distinct keys, no negative balance, total = start + deposits - withdrawals.
New unit tests: `IdempotentRetryTest` (7), and 2 in `TransactionServiceTest` (key looked up only after the locks, via InOrder;
withdraw replay answered although the balance is 0).

Flakiness: `ConcurrencyIT` + `IdempotencyReplayIT` + `DepositRequestConcurrencyIT` (30 tests) run 5 times in a row: 5/5 green, 0 failures.

## 6. Frontend: key reuse rules
New `frontend/src/utils/useIdempotentSubmit.js` (hook, `run(payload, call)`), used by AmountForm (withdraw and admin deposit),
TransferPage and DepositsPage. A key is kept only when BOTH hold: the previous attempt ended with an indeterminate outcome and
the payload (JSON of the sent fields; AmountForm also takes `extraPayload`, the admin deposit passes the wallet id) is unchanged.
* Indeterminate (`isIndeterminate` in `api/client.js`): network error / status 0, 502, 503, 504, and a 500 with no JSON error body
  (`ApiError.hasBody`, set by `parseError`).
* New key after: 2xx, any 4xx (including 429 and 409), a 500 with an error body, any non-ApiError exception, or a changed field.
Tests: `frontend/tests/idempotencyKey.test.jsx`, 27 tests (isIndeterminate table; transfer page: network failure reuse,
502/503/504, bare 500 vs 500 with body, new key after success, after 400/409/422/429, after editing amount/receiver/note;
AmountForm: reuse, 4xx, edit, extra field; deposit request page). The 242 existing tests were not changed.

## 7. Results
| Check | Result |
|---|---|
| `mvn test` | 270 tests (was 261), 0 failures |
| `mvn verify` | 270 unit + 211 integration (was 195; +16), 0 failures, BUILD SUCCESS |
| 5x repeat of the 3 concurrency IT classes | 5/5 green |
| `npm test` | 269 (242 + 27), all pass |
| `npm run build` | OK |

## 8. Remaining risks / not verified
* The retry safety net could not be triggered end to end (the primary fix removes the race); verified by unit tests only.
* The 201 body of a NEW transfer/withdraw/deposit/request now shows the amount with 4 decimals (`10.0000`); clients that
  compared the text `10` would notice (the frontend formats numbers, and its tests pass).
* The frontend key reuse is not tested against a real slow network; "timeout" is covered only as far as fetch rejecting (status 0).
  The client has no request timeout of its own.
* The fix serialises replays of one owner on the wallet row lock (they already were serialised by every money operation).
* The key is kept in memory per mounted form: a page reload after an unknown outcome generates a new key (a real second request).
* Dev backend (8080) runs old code from `target/classes`; I did not restart it, so it still has the old behavior until restarted.
  `target/classes` was recompiled by Maven (no `clean`).
* No password or secret was written to any file.
