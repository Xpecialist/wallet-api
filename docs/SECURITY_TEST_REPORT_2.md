# Security test report 2 (attack-style pass over real HTTP), 2026-10-29

Branch `Testing`, HEAD 1a402d0, working tree clean at the start. No file under `src/` or `frontend/src/` was changed. Nothing committed or pushed. This file is the only file added to the repo.

## Scope and method
- Own instances started from `target/classes` with the classpath from `mvn dependency:build-classpath` (no `mvn clean`, no touching of the dev backend's output): port 8081 with `--app.rate-limit.enabled=false` (functional, token, idempotency, deposit-request and business-logic tests) and port 8082 with default rate limits (several restarts with variants: `login-ip.capacity=1000` to isolate the username bucket, `trust-forwarded-headers=true`, defaults). Profile `dev`, bound to 127.0.0.1 (confirmed with `netstat`: `127.0.0.1:8081`, `127.0.0.1:8082`).
- Database `walletdb_test` only (name verified with `select current_database()` before the TRUNCATE of `deposit_requests, transactions, wallets, users RESTART IDENTITY CASCADE`; `flyway_schema_history` untouched). `walletdb` and `walletdb_old_backup_20261021` were never connected to. The dev backend on 8080 and the dev frontend on 5173 were not attacked; 8080 was confirmed still up afterwards (HTTP 401 on an unauthenticated call, PID 9820 unchanged).
- The admin password was a throwaway passed by environment variable only; it is not in any repo file or in this report. Tokens are redacted.
- Tools: Node 24 `fetch`, raw sockets (for chunked bodies, duplicate headers, request smuggling shapes), curl, psql. Scripts are in the session scratchpad, not in the repo. About 3,500 requests plus a 120,000-request flood.
- Fixture SQL on `walletdb_test` (not application flaws): `UPDATE users SET role='ADMIN'` on one user to get a second admin; `UPDATE users SET token_version=5` and back; `UPDATE deposit_requests SET status='REJECTED'...` to clear pending rows between abuse tests; `UPDATE wallets SET balance=0/10` on one wallet to set up the overflow and parallel-spend tests.
- The dev JWT secret is public in `application-dev.properties`. I used it only to confirm the trust model (a correctly signed token is accepted, a wrongly signed one is rejected).
- Every finding below was reproduced at least twice unless marked otherwise.

## Findings

| # | Sev | Title | New / regression |
|---|---|---|---|
| R1 | High | Every rate limit is bypassed by percent-encoding a character of `/api` (for example `/%61pi/auth/login`) | NEW (introduced by the rate limiter) |
| R2 | Medium | Per-username login limit skipped for login bodies over 8192 bytes | NEW |
| R3 | Low | Victim lockout: anyone can keep a known user from logging in for as long as they like with about 6 requests a minute | NEW (design trade-off of per-username bucket) |
| R4 | Low | A request that passed authentication before a logout can finish after it and mint a fresh valid token (password change) | NEW |
| R5 | Low | No CHECK on `transactions.status`, `users.role`, `wallets.currency`; a bad value is accepted by direct SQL | NEW |
| R6 | Info | Lenient number coercion: `receiverWalletId` as string or float (3.9 becomes 3) | NEW |
| R7 | Info | Transfers to a disabled user's wallet are allowed (also to admin) | NEW (business rule question) |
| R8 | Info | JWT parser leniency (only exploitable by whoever holds the signing key): `tv` of 0.5 accepted as 0, no `exp` accepted, HS256 and HS384 both accepted | NEW |
| R9 | Info | Idempotency-Key accepts control characters (tab) and raw non-ASCII bytes; only the first of two `Idempotency-Key` headers is used | NEW |
| R10 | Info | Weak or non-Base64 `JWT_SECRET` fails startup with a raw stack trace (no failure analyzer for it) | NEW |
| R11 | Info | Per-IP buckets use the exact peer address: an IPv6 client with a /64 can rotate addresses; `max-keys` LRU eviction could be used to reset buckets by a distributed flood | NEW (not testable here) |
| R12 | Info | Misc: JWT in `sessionStorage`; no CSP/Referrer-Policy; Tomcat HTML 400 pages for malformed URLs; bare-LF request accepted; 20 MB request bodies parsed (Jackson default cap); password may contain NUL; admin can approve own deposit request (known) | mixed |

No critical findings. I could not steal money, double-credit anything, read another user's data, escalate privileges, or break conservation of money.

### R1 High: rate limits bypassed with a percent-encoded path
- Endpoints: all rate-limited ones (`POST /api/auth/login`, `/api/auth/register`, `/api/users/me/password`, transfer, withdraw, deposit-requests, admin deposit/approve/reject, and the global per-IP cap).
- Cause: `RateLimitFilter.shouldNotFilter` and `UserRateLimitFilter.shouldNotFilter` test `request.getRequestURI().startsWith("/api/")`. `getRequestURI()` is the raw, undecoded URI. Tomcat normalizes dot segments but does not decode `%61`, so `/%61pi/...` does not start with `/api/` and both filters are skipped, while Spring MVC and Spring Security decode the path and route it to the same controller. (`/x/../api/...`, `/./api/...`, `/api/%6cogin` are all still limited, because the raw URI is normalized or still starts with `/api/`.)
- Reproduction (instance with default limits, a fresh IP bucket; run twice, plus register and withdraw variants):
  ```
  # normal path: 401 x5 then 429
  for i in 1..8: curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8082/api/auth/login -H 'Content-Type: application/json' -d '{"username":"victim","password":"wrong-guess-1"}'
  -> 401 401 401 401 401 429 429 429
  # encoded path: never 429
  for i in 1..8: curl --path-as-is ... localhost:8082/%61pi/auth/login ...
  -> 401 401 401 401 401 401 401 401
  ```
  Further results through `/%61pi/`: 300 parallel wrong-password logins in 2.4 s all 401 (a correct login through it returns 200); 8 registers from one IP all 201 (cap 5/h); 40 parallel withdrawals all 201 (cap 30/min), while the same 40 on `/api/...` gave 30 x 201 and 10 x 429; a normal-path attempt in between still got 429, so the limiter itself works.
- Impact: unlimited online password guessing (about 125 guesses a second against BCrypt on this machine, per attacker connection set), unlimited account creation, BCrypt CPU exhaustion, no cap on money endpoints, deposit-request spam (the pending cap of 5 still holds). This defeats the whole rate-limiting feature for any attacker who knows the trick.
- Suggested fix: decide "is this an API request" with the same matcher Spring uses (for example `PathPatternRequestMatcher` on `/api/**`, or `request.getServletPath()` / `UrlPathHelper.getPathWithinApplication`), or apply the filter to every request and let the individual matchers decide; also reject paths that contain percent-encoded unreserved characters. Add a regression test with `/%61pi/auth/login`.

### R2 Medium: per-username login bucket skipped for large bodies
- Endpoint: `POST /api/auth/login`.
- Cause: `RateLimitFilter.usernameOf` returns null when the body is over `MAX_LOGIN_BODY_BYTES` (8192), so no username bucket is consumed; the controller still parses the full body (extra properties are ignored, Jackson accepts up to 20 million characters).
- Reproduction (instance with `login-ip.capacity=1000` to isolate the username bucket): body `{"username":"carol","password":"wrongpass1","pad":"AAAA... 9000 x A"}`: 30 parallel requests all 401 (no 429); immediately afterwards 30 normal requests for the same user gave 5 x 401 and 25 x 429, which proves the bucket was untouched. Same for `bob`, and with the padding placed before `username`. A body of exactly 8192 bytes is limited correctly.
- Impact: the per-username limit (the defence against a distributed guess of one account) can be skipped. The per-IP bucket (5 per minute) still applies, so a single IP gains nothing; a botnet gains unlimited guesses on one account.
- Suggested fix: when the body exceeds the limit, either reject it with 413/400 for the login endpoint (no legitimate login body is over 200 bytes), or charge a shared "oversized" bucket, or fail closed.

### R3 Low: victim lockout
- Endpoint: `POST /api/auth/login`.
- Reproduction: attacker sends 8 logins for `alice` with a wrong password from any IP; the real user's next correct login gets 429 with `Retry-After: 12`. First success came 12.3 s later (Retry-After is accurate). The attacker keeps the bucket empty with one request every 8 s: over 24 s the real user's correct logins were all 429 while the attacker's requests alternated 429/401.
- Impact: availability of a chosen account can be denied indefinitely for a handful of requests a minute, from anywhere. Inherent to per-username limits; the README documents "unknown usernames counted like known ones".
- Suggested fix (optional): count only failed logins for the username bucket and keep a success path (for example exempt a login that presents a correct password from a known device token), or accept and document the trade-off. Note it also means R2's bypass is the only way to avoid the lockout.

### R4 Low: in-flight request survives a concurrent logout
- Endpoint: `POST /api/users/me/password` racing `POST /api/auth/logout` with the same token.
- Reproduction (run 3 times): fire both at once with one token. Observed: password change 200 (new token), logout 204, final `token_version` +2, and the NEW token returned by the password change works afterwards (200). That happens when the logout bumps first and the password change (already past the JWT filter) bumps second and signs a token with the final version.
- Impact: tiny. The caller still has to know the current password, and an attacker holding a stolen token and the password does not need the race. A revoked token can complete one in-flight state change. Everything else I raced (20 loops of authenticated GET during pw change + logout, 2,428 requests; 100 parallel logouts with one token gave 1 x 204 + 99 x 401 and exactly one version bump; logins in flight during a logout) behaved correctly: the tokens issued before the bump were rejected afterwards.
- Suggested fix: re-check `tv` inside the service transaction (or `SELECT ... FOR UPDATE` the user row and compare to the version the filter saw) before changing the password.

### R5 Low: missing CHECK constraints
- Direct SQL on `walletdb_test`: `insert into transactions(... status) values (... 'DONE')` succeeds, `insert into users(... role) values (... 'SUPER')` succeeds (rolled back). Present and enforced: balance >= 0, amount > 0, type/wallet-shape check, deposit-request status and decision checks, all unique indexes, all FKs.
- Impact: only through direct database access; a row with an unknown enum value would make Hibernate throw when the row is read (a poison row).
- Suggested fix: V3 migration adding CHECKs for `transactions.status`, `users.role` and the currency format.

### R6 Info: lenient coercion
`POST /api/transactions/transfer` with `"receiverWalletId":"3"` or `3.9` returns 201 and pays wallet 3. Not exploitable (the amount and sender are correct), but a float silently becoming another id is surprising. Same for `"amount":"5"` (string accepted everywhere). Fix: disable Jackson coercion of float/string to integral types.

### R7 Info: transfers to disabled users
A disabled user's wallet still receives transfers (201) and deposits; approvals of deposit requests are blocked for disabled users, transfers are not. Decide whether that is intended; the money cannot be spent until the account is re-enabled.

### R8 Info: JWT parsing details (all need the signing key)
With the dev key: correct token accepted; wrong key, `alg:none`, HS512 (key too short), expired, future `nbf`, `crit` header, array subject: all 401. Also: `tv` string "0", -1, 1e30, Long.MAX, null, array, object, true, missing: all 401; `tv` 0.5 and 0.0 accepted as 0 (`Number.longValue`); a token with no `exp` is accepted and never expires; subject case is ignored (`EVE` finds `eve`). `tv` copied from another user's token does not help (it is compared against the subject's own version). Fix: require `exp`, require an integral `tv`.

### R9 Info: idempotency key characters
A key with a tab character and a key with raw bytes 0xFF 0xFE were stored (3 and 4 characters). Non-ASCII UTF-8 and control characters other than tab are rejected by Tomcat (400 HTML page). Two `Idempotency-Key` headers: the first wins. Keys are never echoed, so no injection path; a visible-ASCII validation would be tidier.

### R10 Info: startup failure for a weak secret
No secret: clean "APPLICATION FAILED TO START ... Set the JWT_SECRET environment variable ..." message (OK). A 5-byte secret, a non-Base64 string (jjwt's decoder silently drops invalid characters) or a 31-byte secret also fail fast, but with a raw `WeakKeyException` stack trace. A 32-byte but low-entropy secret is accepted.

### R11 Info: limiter scope
Not testable here (IPv4 loopback only): buckets are keyed by the exact peer address, so an IPv6 attacker with a /64 gets a fresh bucket per address. `max-keys` (50,000 per limiter) bounds memory: with `trust-forwarded-headers=true`, 120,000 requests with distinct spoofed addresses left the working set flat (380 MB before, 339 MB after) and latency unchanged; with trust off, one IP creates at most 5 username keys a minute. A distributed flood of 50,000 keys could evict (reset) a victim's bucket; costs the attacker 50,000 requests.

### R12 Info: misc
- The JWT is in `sessionStorage` (readable by any XSS; there is no XSS sink in the code, see below).
- No `Content-Security-Policy` / `Referrer-Policy` on API responses (irrelevant for JSON, relevant if the SPA is ever served by Tomcat).
- Malformed URLs (`%00`, `%zz` in the path, encoded slash, 70 KB header or URL, `//`) return Tomcat's default HTML 400 page (no version, no stack trace) instead of the JSON shape.
- A bare-LF request is accepted by Tomcat (needs a front proxy to matter).
- Request bodies are not capped by the app; Jackson's default 20,000,000-character string limit is the only bound (a 20 MB username was parsed in 16 s and answered 401; 100 MB gave a clean 400 and connection reset).
- NUL is accepted inside passwords and works consistently (not truncated).
- Admin can create and approve their own deposit request (known; the admin can credit any wallet anyway).
- Retry-After for the register bucket is 720 s (1 h / 5), correct.

## Attacks that were blocked
1. Rate limiting (the paths that are covered): forged `X-Forwarded-For`, `X-Real-IP`, `Forwarded`, `X-Forwarded-Host`, `X-Client-IP` are ignored by default (8 logins with different forged values: 5 x 401 then 429). With `trust-forwarded-headers=true` the leftmost (client-controlled) entries are ignored and only the entry `trusted-proxies` from the right counts; a client that talks directly to the app can of course choose the rightmost value, which is the documented condition. Username variants `VICTIM`, ` victim `, tab/newline padding, Unicode escapes in JSON, duplicate JSON keys (last wins in both limiter and controller, so no mismatch), unknown extra fields, UTF-8 BOM, `charset` parameters, `application/vnd.x+json`, chunked bodies, and `/api/auth/./login`, `/api/auth/../auth/login`, `/api/auth/%6cogin`, `/x/../api/auth/login` all hit the same bucket. Turkish dotted I, dotless i, Kelvin sign, full-width, NBSP and zero-width variants do not match a real account (and do not authenticate). Invalid JSON, `text/plain`, form and multipart on login give 400/415 before any password check. `/API/AUTH/LOGIN`, trailing slash and `;x=1` do not reach the controller (401/404/400).
2. Parallel burst exactness: 40 parallel logins on a fresh username: exactly 5 passed (twice); 50 parallel withdrawals: exactly 30 passed and the balance moved by exactly 30 x 0.01; 150 parallel admin deposits: exactly 120; password-change bucket: 5 wrong attempts then 429, the wrong current password did not bump the token version and the token kept working. Global per-IP cap and register cap enforced on the normal path. 429 bodies carry only a generic message, no username or key; `Retry-After` matches the real refill time.
3. Token revocation: old token after logout, password change (also other sessions), admin disable and re-enable (still revoked): all 401; fresh login works; logout without token 401, with GET 405, with a revoked or garbage token 401; password change with a wrong current password does not bump `tv`; the password-change response carries a fresh working token; `Bearer` spelled differently, `access_token` query parameter, token pasted twice: 401.
4. Idempotency: 25 simultaneous identical transfers: 25 x 201, one id, one row, money moved once (twice, with different keys); 20 simultaneous admin deposits, withdrawals and deposit-request creates with one key: all 201, one id/row, money once; 20 simultaneous different payloads with one key: 5 x 201 (the winner's payload) and 15 x 409, one row; same key on transfer vs withdraw vs admin deposit with a different payload: 409; the same key by two owners: independent; replay with a changed note or receiver: 409; failed originals (422/400) are not stored, so a replay of an insufficient-funds request is re-evaluated; blank key 400, 101 characters 400, 100 characters 201, keys are case-sensitive; replay after the receiver was disabled returns the original; 300 distinct keys: 300 x 201 in 1.4 s.
5. Deposit requests: other users' ids 404 (same as missing); extra fields `walletId`, `userId`, `status`, `decidedBy`, `transactionId`, `id` ignored (request created for the caller's own wallet as PENDING); `userId`/`username` query parameters on the user list ignored; every admin endpoint 403 for a USER; pending cap race with 20 parallel creates: exactly 5 succeeded (15 x 409), later creates with new keys 409; 30 parallel approvals of one request: 1 x 200, 29 x 409, one DEPOSIT transaction; 30 parallel approve/reject by two admins: exactly one winner, one transaction; approving a request of a disabled user 409, rejecting still works; unknown id 404, non-numeric 400; amounts 0, negative, 0.00009, 5 decimals, 1e400, NaN-like strings, 16 digits: 400; notes with NUL, bidi, zero-width, newline, tab, CR, line separator, BOM, DEL, C1, lone surrogate: 400; 256 characters 400; reject without a note 400; admin list `sort=passwordHash`, `user.passwordHash`, SQL fragments in `status`/`userId`/`sort`: 400; `username=%` and `_` do exact (case-insensitive) matching, no wildcard; huge page 400.
6. Round 1/2 regression: fail-fast without `JWT_SECRET` (clear message) and with weak secrets; disabled-login 401 identical in body to wrong password, and timing indistinguishable (150 shuffled samples each: medians 63.2 / 63.3 / 63.3 ms for enabled-wrong-password / disabled / unknown); 404 for foreign wallet and transaction ids; sort whitelist on transactions, admin users, deposit requests; `page` over int range 400, extreme and inverted dates 400; NUL, zero-width, bidi, soft-hyphen, newline in register username/email, login username, transfer note and receiver, deposit-request note, decision note, profile email, new password: 400 (login password NUL gives plain 401); ASCII-only usernames (space, accents, Cyrillic, full-width, NBSP, `/`, `:`, `<b>`, zero-width, soft hyphen rejected); admin-name squatting by case, trailing space, Cyrillic a, dotless i, dotted I: 409 or 400; admin email reserved case-insensitively; 72-byte passwords accepted at register/login/change, 73 bytes and 37 two-byte characters rejected, a 76-byte login password does not match by prefix; multipart, XML, form, text, octet-stream and missing content type: 415 (malformed multipart gives 401/415, no 500); `GET/POST /error` 401 or 403; mass assignment of `role`, `enabled`, `balance`, `id`, `tokenVersion`, `admin`, nested `wallet` on register and profile update ignored (role USER, enabled, tv 0, balance 0); `POST /api/wallets/me/deposit` and variants 404; initial balance 0.
7. Spring Boot surface: no actuator, h2-console, swagger/openapi, jolokia, env, heapdump, devtools or static file exposure (anonymous 401, authenticated 404); path traversal and encoded variants of admin and API paths (`/api/%2e%2e/...` just normalizes to the same route and stays authenticated; `/api/admin;x/`, `//`, `\` rejected 400) never bypass the role check; `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Cache-Control: no-cache, no-store` on every response including errors and 429; no `Server` or `X-Powered-By` header; no HSTS over plain HTTP (expected); malformed and oversized JSON, invalid UTF-8, 100 KB number, 5,000-level nesting: 400 JSON without stack traces; huge header/URL 400; TRACE 405, CONNECT/odd methods 400, PATCH/PUT/DELETE 405 with `Allow`; CORS preflight from a foreign origin or `null`: 401 with no `Access-Control-Allow-*` headers, no CORS headers on normal responses; duplicate or conflicting `Content-Length` 400, `Transfer-Encoding: xchunked` 501, `Transfer-Encoding : chunked` 400, Content-Length plus chunked processed once with no second response (no smuggled request), h2c upgrade ignored; instance bound to 127.0.0.1 only.
8. Database constraints (direct SQL, all rolled back): negative balance, zero and negative amounts, wrong type/wallet shape, bad type, FK violations (including bad wallet and user), duplicate `lower(username)` and `lower(email)`, second wallet per user, duplicate idempotency key per owner (the same key for a different owner is allowed), numeric overflow, null `token_version`, deposit-request status/decision/amount checks, duplicate request idempotency key, deleting a user or wallet that has dependants. `flyway_schema_history`: V1 baseline and V2 user token version, both success.
9. Frontend: grep of `frontend/src` finds no `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function`, `document.write`, `window.open`, `insertAdjacentHTML`, `javascript:`; no `target="_blank"`; no `href`/`src` built from server data; the only `navigate`/`Navigate` calls are fixed paths; ids put into URLs go through `encodeURIComponent` (except `api.transaction(id)` and `setUserEnabled(id)` which take server-provided numeric ids). HTML/script payloads stored in transfer notes, deposit-request notes and decision notes (`<img src=x onerror=...>`, `"><script>`, template syntax, `javascript:`, RTL text) came back from the API byte-identical with `Content-Type: application/json` and are rendered by React as `{value}` text. Emails with `<`, `>`, `"` or spaces are rejected; `'` is allowed. Usernames cannot contain markup (ASCII rule; 0 rows in the database violate it). A 401 clears the token and signs out, 429 shows a fixed message with the Retry-After seconds, logout calls the server (revoking all devices) and ignores a failure.
10. Business logic: negative, zero and 5-decimal amounts rejected; 200 transfers of 0.0001: exactly 0.0200 moved, no rounding drift; self-transfer by name, upper case, padded name and wallet id: 400; unknown receiver by name or id: identical 404; 1e15 balance overflow (admin deposit, deposit-request approval, transfer): clean 400, no 500, request stays PENDING; 90 parallel mixed withdrawals and transfers against a balance of 10: exactly 10 succeeded, balance 0 (run twice); 100 opposing parallel transfers: all 201, no deadlock; only the admin role reaches admin paths.

## Money-conservation check (SQL on `walletdb_test`)
Taken after the first half of the tests (551 transactions, 25 wallets, before I changed any balance by SQL):
- sum of wallet balances 5056.8930 = completed deposits 5061.2000 minus completed withdrawals 4.3070 (transfers net to zero). Deposit-request approvals are part of the deposits (3 approved requests, 57.0000, 3 linked transactions, no transaction linked twice, no approved request without a transaction).
- per-wallet ledger (incoming minus outgoing completed transactions) equals the stored balance for every wallet: 0 mismatches; 0 negative balances; 0 duplicate idempotency keys per owner.

Taken at the end (883 transactions): 0 negative balances, 0 duplicate keys, all wallets but one reconcile. The single mismatch is wallet 6 (the user `victim`), which I reset to 0 and to 10 by SQL for the overflow and parallel-spend tests; that is my fixture, not an application effect. Every parallel test checked its own delta: the balance change always equalled the sum of the 201 responses.

## Dependency and audit results
- OSV batch query (`https://api.osv.dev/v1/querybatch`): 126 Maven artifacts (all scopes, from `mvn dependency:list`) and 153 npm package versions (from `frontend/package-lock.json`): 0 advisories. Control query (log4j-core 2.14.1) returned 8, so the scan can detect issues.
- `cd frontend && npm audit`: found 0 vulnerabilities; `npm audit --omit=dev`: found 0 vulnerabilities. `npm audit fix` not run.
- No new advisory since `VULNERABILITY_SCAN_REPORT.md` (Spring Boot 4.1.2, Tomcat 11.0.27, PostgreSQL JDBC 42.7.14, hibernate-core 7.4.13).

## What I could not test
- IPv6 clients (the instances listen on 127.0.0.1 only), real reverse-proxy chains for `trusted-proxies`, and multi-instance behaviour of the in-memory limiter (each instance counts alone).
- TLS/HSTS (no TLS in the dev setup).
- True OOM behaviour from large bodies: stopped at Jackson's 20 M character limit.
- Running the user's frontend in a browser (code review and HTTP only); the `dist` build was not inspected.
- Fully deleted users (foreign keys prevent deleting a user who has a wallet, so that path is unreachable without dropping constraints).
- Wall-clock token expiry (1 hour) beyond forged expired tokens.
- The `dev` secret trust model beyond confirming signed tokens are accepted: I did not craft an admin token.

## Cleanup
Instances on 8081 and 8082 stopped (PIDs 19724 and 13616; earlier 8082 restarts were stopped before each new start). The dev backend on 8080 (PID 9820) is still listening and answers 401 unauthenticated. `walletdb_test` is left populated with the test data.
