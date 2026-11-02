# Deposit requests with admin approval (2026-10-21)

Decision: self-service deposit stays removed. A user may ask for money to be added; it is credited only when an admin
approves (for example after receiving a bank transfer). The admin deposit (`POST /api/admin/wallets/{id}/deposit`) is
unchanged.

## Design summary

- New table `deposit_requests`, entity `DepositRequest`, repository `DepositRequestRepository`, service
  `DepositRequestService`, controllers `DepositRequestController` (`/api/deposit-requests`) and
  `AdminDepositRequestController` (`/api/admin/deposit-requests`, covered by the existing `/api/admin/**` ADMIN rule).
  `AdminController` and `TransactionService` were not changed.
- Money rules are the same as for deposits: `BigDecimal`, `@NotNull @DecimalMin("0.0001") @Digits(15,4)`, DB
  `NUMERIC(19,4)` with `CHECK (amount > 0)`.
- The wallet always comes from the token (`findByUserUsername`); no wallet or user id is accepted from the client.
  Foreign and missing ids are both 404 with the same body.
- Pending cap: `app.deposit-requests.max-pending` (default 5) per user -> 409 "Too many pending deposit requests".
- `Idempotency-Key` (optional, 1..100 chars, per wallet): same key + same amount + same note returns the original request
  (201), different payload -> 409. A replay is answered before the cap check. The key is never returned.
- Note and decision note are trimmed; blank becomes null. Reject requires a non-blank note.
- Disabled user: approve -> 409 "User is disabled; the request can only be rejected"; reject is allowed. (A disabled
  user cannot log in or use a token, so they cannot create requests either; their old PENDING requests stay visible to
  admins.)
- Admins may request for themselves and approve their own requests (see risks).
- Admin list default (no `sort`, no `status`): PENDING first, then newest. With `status` or `sort` the normal sort applies
  (`@PageableDefault(size=20)` without a default sort so the PENDING-first rule can detect "no explicit sort").
- Approved deposit: normal `transactions` row (`DEPOSIT`, `COMPLETED`, receiver = wallet, no key), `note` =
  `Deposit request #<id>[: <user note>]` (cut to 255). It shows in the user's history; the request holds `transactionId`.

## Endpoints

| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/api/deposit-requests` | USER/ADMIN | `{amount, note?}`, header `Idempotency-Key?` | 201 `DepositRequestResponse` (PENDING) | 400 invalid amount/note/key, 401, 404 no wallet, 409 key reuse with other payload or pending cap |
| GET | `/api/deposit-requests` | USER/ADMIN | `page,size(<=100, default 20),sort(id,createdAt,amount,status,decidedAt),status` | 200 page of own requests, default `createdAt,desc` | 400 bad sort/status, 401 |
| GET | `/api/deposit-requests/{id}` | USER/ADMIN (own only) | - | 200 `DepositRequestResponse` | 401, 404 missing or foreign |
| GET | `/api/admin/deposit-requests` | ADMIN | paging as above + `status,userId,username` | 200 page of `AdminDepositRequestResponse` | 400, 401, 403 |
| POST | `/api/admin/deposit-requests/{id}/approve` | ADMIN | optional `{decisionNote<=255}` | 200 `AdminDepositRequestResponse` (APPROVED, `transactionId`) | 400 note too long, 401, 403, 404, 409 already decided / user disabled |
| POST | `/api/admin/deposit-requests/{id}/reject` | ADMIN | `{decisionNote}` required, <=255 | 200 `AdminDepositRequestResponse` (REJECTED) | 400 missing/blank/too long, 401, 403, 404, 409 already decided |

`DepositRequestResponse`: `id, walletId, amount, note, status, createdAt, decidedAt, decisionNote, transactionId`.
`AdminDepositRequestResponse` adds `userId, username, decidedBy`. Neither contains the idempotency key.

## Exactly-once approval

`DepositRequestService.approve` is one `@Transactional` method:

1. `DepositRequestRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)` = `SELECT ... FOR UPDATE`) locks the request
   row; this is the first load of that entity in the transaction, so the status is read fresh.
2. Status is re-checked under the lock; not PENDING -> 409 "Deposit request already <STATUS>", nothing else happens.
   Any concurrent approve/reject (double click, two admins) blocks on the row lock, then sees the committed status.
3. Disabled owner -> 409.
4. Wallet locked with the existing `WalletRepository.findByIdForUpdate` (lock + refresh), balance increased,
   `COMPLETED` `DEPOSIT` transaction saved, request set to APPROVED with `decided_by`, `decided_at`, `transaction_id`.
5. Commit releases both locks. Any exception rolls back everything.

Lock order is request row, then wallet row. Transfers/withdrawals/admin deposits lock wallet rows only (transfers in
ascending id order), so there is no cycle. Creating a request locks the caller's wallet row and then inserts a new
request row nobody else can hold, so it cannot form a cycle either. Creation takes that wallet lock deliberately: it
serialises one user's creates, so the pending cap and the idempotency lookup cannot be raced (no 5xx and no unique
violation for parallel same-key calls; the cap is exact).
Last safety net in the database: `uq_dr_transaction` (a transaction can back only one request) and `chk_dr_decision`
(an APPROVED row must have decider, time and transaction).

## Schema changes (`schema.sql`, idempotent)

`CREATE TABLE IF NOT EXISTS deposit_requests` (id, wallet_id FK, user_id FK, amount `NUMERIC(19,4)` CHECK > 0, note
`VARCHAR(255)`, status CHECK in PENDING/APPROVED/REJECTED, created_at, decided_at, decided_by FK users,
decision_note `VARCHAR(255)`, transaction_id FK transactions, idempotency_key `VARCHAR(100)`, `chk_dr_decision`) and
indexes `idx_dr_wallet`, `idx_dr_status`, `uq_dr_wallet_idempotency` (wallet_id, key where key not null),
`uq_dr_transaction`. Nothing existing is altered; a database without the table gets it on the next startup.
New property `app.deposit-requests.max-pending=5`. The dev database `walletdb` was not touched; it gets the table when
the new code is started against it.

## Files changed

Backend (new): `entity/DepositRequest`, `entity/DepositRequestStatus`, `repository/DepositRequestRepository`,
`service/DepositRequestService`, `controller/DepositRequestController`, `controller/AdminDepositRequestController`,
`dto/CreateDepositRequest`, `dto/DepositDecisionRequest`, `dto/DepositRequestResponse`,
`dto/AdminDepositRequestResponse`.
Backend (edited): `schema.sql`, `application.properties`, `service/SortWhitelist` (one set).
Frontend (new): `pages/DepositsPage.jsx`, `components/StatusBadge.jsx`, `tests/deposits.test.jsx`.
Frontend (edited): `api/client.js`, `App.jsx` (routes `/deposits`, `/admin/deposit-requests`), `components/Layout.jsx`
(nav link), `pages/AdminPages.jsx` (`AdminDepositRequestsPage`, sub-nav link), `pages/DashboardPage.jsx` (Add funds card),
`styles.css`.
Docs: `README.md`, `frontend/README.md`, `TEST_FINDINGS.md` (short pointer), this file.
Tests infrastructure (edited): `it/ItBase` (truncate list), `it/TestDbGuard` and `it/SchemaUpgradeIT` (drop list), so the
new table is rebuilt with its foreign keys in `walletdb_test`; no existing assertion was changed.

## Tests added

- `DepositRequestServiceTest` (15 unit): create, cap, replay/mismatch/key validation, get/list guards, approve (credit,
  linked transaction, lock order, non-pending, disabled user), reject.
- `DepositRequestIT` (19, `walletdb_test` only): create/list/get own, foreign vs missing 404 identical, filters, sort,
  size cap, bad sort (incl. `user.passwordHash`), validation (zero, negative, 5 decimals, too large, note 256, missing
  amount), cap per user and freed by a decision, idempotency (replay, mismatch, per owner, key length, replay at cap),
  approve credits once + history + link, approve without body, reject (note required, balance unchanged), double
  approve/reject, approve after reject, unknown id, disabled user, admin for self, admin list (pending first, status,
  username, userId, sort, no secrets), USER 403 / anonymous 401, wallet taken from token only.
- `DepositRequestConcurrencyIT` (5): 30 parallel approvals of one request -> exactly one 200, 29x 409, credited once;
  mixed approve/approve/reject by two admins over 5 rounds -> exactly one decision each; 20 parallel approvals (each
  request twice) of 5x4 requests of one wallet -> exact sum 126, 20 transactions, 20 distinct links; approvals mixed with
  transfers, admin deposits and creates -> no 5xx, no deadlock, sum correct; 20 parallel creates -> exactly 5 succeed,
  10 parallel same-key creates -> one row.
- `SchemaUpgradeIT` (+1): old database lacks the table, `schema.sql` adds table, indexes and constraints; data untouched.
- Frontend `tests/deposits.test.jsx` (30): nav and dashboard link, form payload/trim/idempotency key/new key per submit,
  amount validation, note limit, double submit (click and Enter), cap error, list with badges/dates/decision note/params,
  empty and error states, status filter, paging, wallet refresh; admin list default PENDING, filter, buttons only for
  PENDING, approve (disabled while pending, reload), already-decided error, reject (required reason, cancel, failure),
  route protection (USER redirected, anonymous to login, admin link).

## Results

| Command | Result |
|---|---|
| `mvn test` | 174 tests, 0 failures (was 159) |
| `mvn verify` | 174 unit + 133 integration (was 108), 0 failures, BUILD SUCCESS |
| `cd frontend && npm test` | 8 files, 217 tests passed (was 187) |
| `cd frontend && npm run build` | ok |

(The "APPLICATION FAILED TO START" lines in the `mvn verify` output come from `JwtSecretStartupIT`, which asserts that
failure.)

## Remaining risks and things deliberately not done

- An admin can approve their own request (no four-eyes rule). Admins can already credit any wallet, so no new power, but
  it is not audited beyond `decided_by`.
- No expiry or auto-cancel of old PENDING requests, and users cannot cancel their own request (admin rejects it).
- Amount of a request is not bounded below the DB limit; the cap of 5 pending per user limits spam but a user can still
  create requests slowly forever. No rate limiting (already an open item in `TEST_FINDINGS.md`).
- The disabled-user check reads the flag loaded under the request lock; a user disabled in the same millisecond as the
  approval commit may still be credited (the wallet remains theirs; harmless, the admin action was explicit).
- A balance close to the `NUMERIC(19,4)` limit makes approval fail with 400 "Value out of range" (same as admin deposit).
- No detail view for requests in the UI, no email/notification on decision; the frontend shows the new
  balance only after the wallet is refreshed (done when the Deposits page opens and after other money actions).
- Admin list filter `username` is an exact match (ignoring case), not a search.
- `scripts/seed-dev-data.sh` appeared in the working tree during this task; it is not part of this change.
