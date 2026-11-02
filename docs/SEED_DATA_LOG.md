# Seed data log

## What changed and why
`scripts/seed-dev-data.sh` was rewritten into a robust, re-runnable demo-data seeder (README section "Demo data" added). Old problems: ~6 quick logins from one IP hit the 5/min login limit (429, then "login failed"); registration (5/hour per IP) made a second run fail; re-running created duplicate transfers.

## Behavior
- Config: `API` (default http://localhost:8080), `ADMIN_USER` (admin), `ADMIN_PASSWORD` (required, never printed), `SEED_PASSWORD` (dev-only default, reminder printed), `SEED_ALLOW_REMOTE=1` required for a non-localhost API, `SEED_PACE` (sleep after each request, default 0.2 s).
- Preflight: API reachable and admin login works, with distinct errors (unreachable / wrong credentials). Tokens and passwords are never printed.
- Every request goes through `req()`: curl `-D` header dump; on 429 it reads `Retry-After`, prints "waiting Ns (rate limit)", sleeps and retries (max 6 attempts). If Retry-After is over 180 s it stops (exit 2) with the instruction to restart the backend with `--app.rate-limit.enabled=false` (exact `mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--app.rate-limit.enabled=false"`), then restart normally.
- Users: login first, register only when login gives 401 (so re-runs do not use the 5/hour registration budget); 409 on register is tolerated. A user that cannot log in but is disabled (mallory on re-run) is skipped, including transfers to or from it; any other login failure aborts with a message about SEED_PASSWORD.

## Dataset
| Item | Count | Detail |
|---|---|---|
| Users | 11 (+ admin = 12) | alice, bob, carol, dave, erin, frank, grace, heidi, ivan, judy, mallory (`<name>@example.com`) |
| Admin deposits | 11 | 150-5000 range, e.g. alice 5000.00, bob 3200.50, mallory 275.50 |
| Transfers | 41 | notes like rent, dinner, coffee, groceries, concert tickets, birthday gift, split bill, paid back; cents amounts |
| Withdrawals | 8 | alice, bob, carol, dave, frank, heidi, ivan, grace |
| Deposit requests | 8 | PENDING 3 (alice, dave, judy), APPROVED 3 (frank, heidi, erin, with decision notes), REJECTED 2 (grace "Payment not received", ivan) |
| Disabled | 1 | mallory, by the admin status endpoint, last |

Order: users, deposits, transfers, withdrawals, requests, decisions, disable. Amounts are designed so nothing hits insufficient funds. Transactions per user: alice 15, bob 15, carol 12, dave 10, heidi 10, erin 9, frank 9, judy 7, grace 7, ivan 6, mallory 4 (10 per page History paging is visible for most).

## Re-run idempotency
Fixed keys: `seed-v2-deposit-<user>`, `seed-v2-transfer-NNN` (position in the script), `seed-v2-withdraw-<user>`, `seed-v2-request-<user>-1`. Keys are per owner; same key and same payload returns the original, so nothing is added. Request creation replay returns the original request id, which is then approved/rejected; a 409 "already decided" counts as skipped. Disabling an already-disabled user is OK. Do not change an existing seed's amount or note: the same key with a different payload gives 409 (abort). Changing `SEED_PASSWORD` after the first run makes existing users unreachable (abort with a message).

## Test evidence (own backend on port 8081, database `walletdb_test` only, throwaway admin password in the environment)
- DB verified as `walletdb_test`, then `truncate deposit_requests, transactions, wallets, users restart identity cascade`.
- (a) Default limits, fresh DB: users alice..erin registered, several "waiting 7-11s (rate limit)" lines, then registering frank hit Retry-After=636 s: exit 2 with the restart message. Login 429 waits worked.
- (b) Backend restarted with `--app.rate-limit.enabled=false`: full run, exit 0. Summary: users 12, transactions 63, PENDING 3, APPROVED 3, REJECTED 2.
- (c) Second run, exit 0, counts before/after identical (`users|transactions|deposit_requests|sum(balance)` = `12|63|8|18087.4000` both times; diff empty). Mallory was already disabled and its activity skipped.
- (d) SQL: negative balances 0; sum(balances) 18087.4000 = completed deposits (14: 11 admin + 3 approved) minus completed withdrawals (8) = 18087.4000; transactions DEPOSIT 14, TRANSFER 41, WITHDRAWAL 8, all COMPLETED; requests APPROVED 3, PENDING 3, REJECTED 2; mallory enabled = false.
- `bash -n` OK; shellcheck not installed. Instances stopped; ports 8080, 8081, 5173 not listening.
- Note: the typo fix in the decision line ("decision approve recorded for ...") was made after the final run; syntax re-checked with `bash -n` only.

## Known limits
- Timestamps are all "now": the API cannot backdate.
- Cannot run against remote hosts without `SEED_ALLOW_REMOTE=1`.
- Fresh DB with default limits cannot finish (6 registrations > 5/hour): use the limits-off restart once.
- Data is in the DB, not in git: each environment must run the script once.
- Mallory's own transfers are skipped on re-run (the user is disabled), which is fine because they already exist.
