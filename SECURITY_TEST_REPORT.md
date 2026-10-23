# Security test report (attack-style pass), 2026-10-21

Branch `Testing`, current working tree. No source files were changed. Nothing committed or pushed.

## Scope and method
- Own backend instance on `localhost:8081`, `dev` profile, database `walletdb_test` only (name verified with `select current_database()` before the TRUNCATE of `deposit_requests, transactions, wallets, users`). The dev backend on 8080 and the dev frontend on 5173 were not attacked; 8080 was confirmed still up (HTTP 401 on an unauthenticated call) after my instance was stopped.
- Real HTTP with curl, Node `fetch` and raw sockets. About 600 requests. Scripts are in the session scratchpad, not in the repo. Tokens are redacted below.
- A second admin was created for the two-admin race tests by a SQL `UPDATE users SET role='ADMIN'` on `walletdb_test` (fixture setup, not an application flaw). SQL was also used on `walletdb_test` to delete a user/wallet to test the deleted-user and missing-wallet paths.
- Known items (M1, M5, M6, M7, L4, L8 to L11, simultaneous same-key replay 409, admin approves own request, no request expiry/cancel) are only mentioned where I found something new about them.
- Every finding below was reproduced at least twice unless marked otherwise.

## Findings

No critical or high findings. I could not steal money, escalate privileges, read another user's data or double-credit anything.

| # | Sev | Title | New / known |
|---|---|---|---|
| F1 | Medium | `dev` profile secret is public and the app listens on all interfaces (`0.0.0.0`) | Known (H1 residual) plus NEW detail (network binding) |
| F2 | Low | NUL character (`\u0000`) in any stored/queried string gives HTTP 500 | NEW |
| F3 | Low | Disabled-account timing oracle at login (2 ms vs 75 ms) | NEW (M2 follow-up) |
| F4 | Low | Large `page` values give HTTP 500 (offset overflow) | NEW |
| F5 | Low | Extreme `from`/`to` dates give HTTP 500 (confirmed at runtime) | Known (L8), now reproduced |
| F6 | Low | Direct `GET /error` returns 500 with `status: 999` to anonymous callers | NEW |
| F7 | Low | Malformed `multipart/form-data` on JSON endpoints gives HTTP 500 | NEW |
| F8 | Low | Invisible/control/bidi/look-alike characters allowed in usernames and notes | Known (L1 homoglyphs) plus NEW detail |
| F9 | Low | Wallet-id and username existence oracle via transfer errors | NEW (minor) |
| F10 | Low | Spring Security 6.3.4 BCrypt matches passwords longer than 72 bytes by prefix | Known dependency class (M5), behaviour NEW-confirmed |
| F11 | Low | Frontend npm audit: 8 advisories (1 high, 2 critical), all dev-tooling except react-router | NEW (audit result) |
| F12 | Info | Misc hardening observations | mixed |

### F1 Medium: public dev JWT secret plus all-interface binding
- Endpoint: whole API. `application-dev.properties` holds a committed secret; `server.address` is unset, so Tomcat binds `0.0.0.0` (`netstat`: `0.0.0.0:8081 LISTENING`; the dev server on 8080 is also `0.0.0.0:8080`).
- Observed: with the dev profile, anyone who can reach the port and has read the repo can sign tokens for any existing username (including `admin`). I did not craft an admin token; I only confirmed, with a token signed using the dev key for `alice` (HS256, and HS384 with an expired `exp`), that the server trusts that key and checks expiry. The dev instance on 8080 runs this profile and is reachable from the local network, not just localhost.
- Expected: dev profile reachable only from localhost.
- Impact: any machine on the same network as the developer's PC can become admin on the dev server and mint money there.
- Fix: add `server.address=127.0.0.1` to `application-dev.properties`. Optionally generate a random secret at startup in dev.
- Repro: `netstat -ano | findstr :8080` shows `0.0.0.0`.

### F2 Low: NUL character gives 500
- Endpoints: `POST /api/auth/register` (username), `POST /api/auth/login` (username), `POST /api/transactions/transfer` (`note`, `receiverUsername`), `POST /api/deposit-requests` (`note`), `POST /api/admin/deposit-requests/{id}/reject` (`decisionNote`).
- Repro: `curl -X POST localhost:8081/api/auth/login -H 'Content-Type: application/json' -d '{"username":"al\u0000ice","password":"x"}'` returns `500 {"status":500,"error":"Internal error"}`. Same for register with `"nul\u0000x"` (reproduced several times).
- Cause: PostgreSQL rejects 0x00 (`invalid byte sequence for encoding "UTF8": 0x00`, SQLState 22021), and the `integrity` handler returns 500 for anything but 23505/22003/22001. Leading/trailing NUL is stripped by `trim()`, so only interior NUL triggers it. The transaction rolls back; no data was changed.
- Impact: an anonymous caller can generate error-level logs with stack traces at will (log noise, alert fatigue). No data impact.
- Fix: map SQLState 22021 to 400, and/or add a pattern constraint rejecting control characters in username, notes and decision notes.

### F3 Low: disabled-account timing oracle
- Endpoint: `POST /api/auth/login`.
- Repro (5 requests each, run twice): wrong password for a disabled user about 2.3 ms; wrong password for an enabled user about 75 ms; unknown user about 80 ms (so unknown and enabled users are indistinguishable, good). The disabled user is the outlier because Spring checks `enabled` before BCrypt.
- Impact: the 401 body hides disabled accounts (M2 fix), but timing reveals which usernames exist and are disabled. Needs a valid username guess first, so it is minor.
- Fix: perform a dummy BCrypt match when the account is disabled, or check the password first and then the enabled flag.

### F4 Low: large page numbers give 500
- Endpoints: `GET /api/transactions`, probably all paged endpoints.
- Repro: `curl -H "Authorization: Bearer <T>" 'localhost:8081/api/transactions?page=99999999&size=100'` returns 500. `page=2147483647` (default size) also 500, `page=1073741824&size=2` 500. `page=100000000` is 200 with empty content.
- Cause: page times size overflows the int offset. Not tested on the other paged endpoints, but they use the same resolver.
- Fix: cap `page` (for example `spring.data.web.pageable.max-page` or a clamp in the controller) or catch the exception and return 400.

### F5 Low: extreme dates give 500 (known L8, now confirmed)
- Repro: `GET /api/transactions?from=%2B999999999-12-31` returns 500 `Internal error`; `from=-999999999-01-01` 500; `to=%2B999999999-12-31` 500 (Spring default error body, from `plusDays` overflow). Same on `/api/admin/transactions`. `to=9999-12-31` is fine.
- Fix: bound the dates (for example year 1900 to 2200) and return 400.

### F6 Low: `GET /error` returns 500
- `/error` is `permitAll` (documented). Calling it directly: `curl -i localhost:8081/error` returns `500 {"status":999,"error":"None"}` to anonymous callers. Harmless but looks like a server failure and may trip monitoring.
- Fix: a handler or filter returning 404 for direct (non-forwarded) `/error` calls.

### F7 Low: malformed multipart gives 500
- Repro: `curl -X POST localhost:8081/api/auth/login -H 'Content-Type: multipart/form-data; boundary=x' -d $'--x\r\n'` returns 500 (`MalformedStreamException` in the log). Anonymous.
- Fix: handle `MultipartException`/`MaxUploadSizeExceeded` as 400, or disable multipart (`spring.servlet.multipart.enabled=false`).

### F8 Low: look-alike and invisible characters
- Repro: register `alice​` (zero width space), `ali­ce` (soft hyphen), `bad\nuser` (newline inside the name), Cyrillic `аlice`, fullwidth `ａlice`: all 201. A note containing `‮` (bidi override) is stored and returned.
- Homoglyphs were a known remaining risk after L1. New detail: zero-width, soft-hyphen, newline and bidi characters make impersonation easier (a name that looks identical to `alice` in the UI) and allow username-based transfers to be misdirected.
- The reserved admin name held: `admin`, `ADMIN`, `" Admin "`, Turkish `ADMİN` and dotless `admın` all gave 409.
- Fix: restrict usernames to a conservative pattern (letters, digits, `._-`), or NFKC-normalise and reject control/format characters (categories Cc, Cf).

### F9 Low: existence oracles on transfer
- `POST /api/transactions/transfer` with an unknown `receiverUsername` gives 404 "Receiver not found"; with an unknown `receiverWalletId` gives 404 "Receiver wallet not found"; with an existing wallet and an amount above balance gives 422. So any user can enumerate valid wallet ids (sequential) and usernames. It also maps username to wallet id (the response contains `receiverWalletId`). Wallet id to username is not possible (404 for non-owners).
- Registration 409s also reveal usernames and emails (already noted as inherent).
- Impact is small. Fix would be rate limiting (M1).

### F10 Low: BCrypt prefix match (Spring Security 6.3.4)
- Repro: register with a 72-character password `a`x72, then `POST /api/auth/login` with 73 `a` returns 200. Registration correctly refuses over 72 bytes, so only users with a 72-byte password are affected, and the attacker still needs those 72 bytes. This matches the Spring Security issue fixed in later 6.3.x (from my memory, unverified). Resolved by upgrading (M5). Optionally add the same 72-byte limit to the login DTO.

### F11 Low: `npm audit` (frontend), see the dependency section.

### F12 Info observations
- JSON strings given as amounts are accepted: `"amount":"10"`, `"1e2"`, `"1 "`, `" 1"` all moved money. A float wallet id is truncated: `"receiverWalletId":2.5` is treated as wallet 2. Lenient Jackson coercion; set `spring.jackson.mapper.allow-coercion-of-scalars=false` and `deserialization.accept-float-as-int=false` if strictness is wanted.
- Duplicate JSON keys: last one wins (`amount` 1 then 2 sent 2). Trailing garbage after the JSON document is ignored. Two `Authorization` headers: the first is used.
- Tokens without an `exp` claim are accepted (only forgeable with the signing key). A JWT identifies the user by username, not id; if users were ever deletable and a name re-registered, an old token would map to the new account (reproduced via SQL delete; no delete API exists).
- No request body size limit: a 50 MB JSON register request was accepted (201, 316 ms) because unknown fields are skipped. Jackson's 20M-character string limit blocked eight parallel 400 MB `username` bodies (400 in 0.2 s) and the instance stayed healthy. Add `server.tomcat.max-http-form-post-size` / a body filter if a hard cap is wanted.
- Error text for filter parameters leaks internal class names (`com.example.wallet.entity.TransactionType`, `java.time.LocalDate`) on `GET /api/transactions?type=x`.
- `email` validation accepts `a@b` and quoted local parts (`"a b"@example.com`).
- Idempotency key namespace per wallet is shared between a user's own outgoing ops and admin deposits to that wallet; a user cannot know the admin's key, so low risk. Deposit-request keys are a separate namespace.
- Wallet at the NUMERIC limit (`999999999999999.9999`) can receive nothing more: deposit, transfer and approve all return 400 "Value out of range" with nothing changed (no 5xx). A request that would overflow stays PENDING until rejected.
- A rejected deposit request frees a pending slot; combined with no rate limiting a user can keep creating requests.
- Concurrent password changes all succeed (last write wins); harmless.
- Approve racing with disable both returned 200 (documented in DEPOSIT_REQUESTS_LOG.md).
- Response headers on authenticated responses: `Cache-Control: no-cache, no-store`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, no `Server` header, no cookies. No HSTS (plain HTTP in this setup) and no CSP (JSON API; matters only if the frontend is served by this app).

## Attacks that were blocked
- **JWT:** `alg=none` (several spellings), wrong key, flipped signature byte, tampered `sub`/claims with the old signature, expired and just-expired tokens, future `nbf`, `crit` header, null/numeric/SQL-injection `sub`, 2 and 5-part tokens, garbage: all 401. `role`/`roles` claims are ignored (role comes from the DB each request; a live demotion took effect immediately: 200 then 403). `kid`/`jku` headers are ignored. Disabled and deleted users' tokens: 401. Tokens in query string, cookie, custom headers or body: ignored (401). Bad header forms (`bearer`, `Basic`, double space, 100 KB header): 401 or Tomcat 400 with no version info.
- **Mass assignment:** register with `role`, `enabled`, `balance`, `id`, `wallet`, `currency`: user is USER, balance 0, admin endpoints 403. `PATCH /api/users/me` with `role`/`username`/`enabled`/`id`: ignored. Deposit/transfer bodies with `currency`, `type`, `senderWalletId`, `walletId`, `userId`: ignored, sender and wallet always come from the token.
- **IDOR:** another user's wallet, transaction and deposit request by id: 404 identical to missing. Admin cannot read users' deposit requests through the user endpoint (404). Lists contain only the caller's own rows even with `userId`/`walletId` query params. Admin endpoints for anonymous 401 and USER 403 across all 6 admin routes (matrix of 20 endpoints x anonymous/user/admin matched the README).
- **Method and path tricks:** PUT/DELETE/PATCH/OPTIONS/HEAD/TRACE gave 405/403/400 with no bypass. `//`, `/./`, `/../`, `;x=1`, `..;/`, `%2f`, `%2e%2e`, `%00`, trailing slash, `.json`, case variants (`/API/ADMIN/...` is 404/401 because routing is case sensitive too), encoded `%61dmin` (still 403): no bypass.
- **Recon:** `/actuator/**`, `/h2-console`, `/swagger-ui`, `/v3/api-docs`, `.env`, `.git/config`, `WEB-INF` and about 20 other paths: 401/404, nothing exposed. No stack traces in any response.
- **CORS/CSRF:** foreign `Origin` gets no CORS headers, preflight to protected paths 401; no cookies are ever set; form/text/XML content types give 415. Host and `X-Forwarded-Host` spoofing had no effect.
- **Injection:** SQL injection strings in every filter, sort, path, body and username parameter: 400/404/empty, tables intact; sort whitelist held (`passwordHash`, `idempotencyKey`, `user.email`, `note`, `wallet.balance`, `(select 1)` all 400); admin `username` filter is an exact match (`%`, `_` and `' OR 1=1--` return nothing). Header injection: CR/LF in `Idempotency-Key` cannot split the response; NUL and non-ASCII bytes in headers are rejected by Tomcat (400). CL+TE request got 400.
- **JSON abuse:** 10 000/100 000 nesting depth, arrays/objects as strings, invalid UTF-8, BOM, comments, single quotes: 400 or 401, never 5xx (apart from F2).
- **Money logic:** negative, zero, `-0`, 5 decimals, `NaN`, `Infinity`, `1e400`, `1e999999999`, `1e-999999999`, 16-digit amounts, hex, Arabic digits, `+1`, `.5`: all 400 and fast (no slow parsing). Max amount `999999999999999.9999` accepted exactly once; overflow of NUMERIC via deposit, transfer or approval gives clean 400 with the balance unchanged. Self-transfer by name, `ALICE`, ` alice `, wallet id, and both fields mixed: all 400. Currency in body ignored.
- **Idempotency:** key reuse with another amount, receiver, type (transfer vs withdraw vs deposit) gives 409; same key from two users is independent; a replay returns the original; 100-character key accepted, 101 and 5000 are 400, blank is 400, SQL/percent/Unicode content stored safely.
- **Races (all exact):** 60 parallel transfers of 10 from a balance of 100: exactly 10 x 201, 50 x 422, balance 0. 60 mixed withdraw/transfer: exactly 10 succeed. 20 parallel same-key transfers: money moved once (the loser 409 behaviour is the known item). Deposit request approvals, 5 rounds of 4 approves plus 3 rejects across two admins: exactly one winner each round, rest 409, credited exactly once (balance 35 for 5 x 7). 40 parallel creates by one user: exactly 5 created. 20 parallel registrations of one username (mixed case) and of one email: exactly one 201. Approve of a disabled user's request: 409; reject allowed.
- **Deposit requests:** pending cap held; HTML/script/SQL/CRLF/formula/`javascript:` notes are stored and returned as plain data in `application/json` (CRLF is escaped in JSON, no header injection); 256-character notes, and emoji notes counted in UTF-16 units, rejected with 400; wallet or user ids in the body ignored.
- **Frontend code review:** no `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `document.write`, no hrefs or `src` built from user data; user-controlled text (usernames, notes, decision notes, server error messages) is rendered as JSX text, which React escapes. Not verified in a browser.

## Money-conservation check (SQL on `walletdb_test`, after all tests)
- Sum of wallet balances: 1000000000003300.9986
- Sum of DEPOSIT amounts minus sum of WITHDRAWAL amounts: 1000000000003667.0001 - 366.0015 = 1000000000003300.9986. **Equal (conserved = true)**; transfers net to zero.
- No wallet with a negative balance; 0 APPROVED requests without a transaction; no transaction linked to two requests; 8 APPROVED requests (sum 50.0000) match 8 `Deposit request #...` transactions (sum 50.0000); no duplicate idempotency keys per owner.

## Dependencies and audit
`mvn dependency:tree` (resolved versions): spring-boot 3.3.5, spring-security-core/web/config 6.3.4, spring-web/webmvc/core/orm 6.1.14, tomcat-embed-core 10.1.31, jackson-databind/core 2.17.2, postgresql JDBC 42.7.4, jjwt 0.12.6, hibernate-core 6.5.3.Final, hibernate-validator 8.0.1.Final, HikariCP 5.1.0, logback-core 1.5.11, snakeyaml 2.2, spring-data-jpa 3.3.5.

- OWASP dependency-check (`org.owasp:dependency-check-maven:check`) could not run: it requires an NVD API key (`NvdApiException: Invalid API Key`) and I did not download the NVD feed. No scanner-verified CVE list for the Java side.
- **Unverified, from my own memory, to be confirmed by a scanner after an upgrade (M5):** Tomcat 10.1.31 and Spring Security 6.3.4 and Spring Framework 6.1.14 are all behind later security releases. The ones I recall that could matter: Spring Security BCrypt over-72-byte match (behaviour confirmed here, F10); several Tomcat 10.1.x fixes in 2025 (partial PUT handling, HTTP/2, multipart-related DoS); logback 1.5.11 configuration-file issues fixed in 1.5.13; Spring Framework 2025 advisories for 6.1.x; pgjdbc 42.7.4 fixes in later 42.7.x. Most need non-default configuration or attacker control of config files, so exploitability here is probably low. Treat as "upgrade to a supported Boot line" rather than a confirmed exploitable issue.
- **`npm audit` (frontend, not fixed, `--force` not run): 8 advisories (5 moderate, 1 high, 2 critical).**
  - vite 5.4.21 (high, direct): path traversal in optimized deps `.map` handling, `server.fs.deny` bypass on Windows, launch-editor NTLM hash disclosure. Dev server only.
  - vitest 2.1.9 (critical, direct) and tinypool (critical): Vitest UI server arbitrary file read/execute, prototype-pollution gadget to RCE. Test tooling only; do not run Vitest UI on a shared network.
  - esbuild 0.21.5 (moderate): any website can send requests to the dev server and read responses. Dev only.
  - react-router-dom 6.30.6 (moderate, production dependency): open redirect via backslash in `<Link>`/`useNavigate`, and an SSR-hydration issue (not used; the app is client-side only). The app does not build links from user data, so exploitability is low.
  - `npm audit --omit=dev` shows only the 2 react-router items.
- Config review: `application.properties` has no default JWT secret (good); DB falls back to `postgres/postgres` over plain JDBC (M7); `spring.sql.init.mode=always` (M6); no `server.address`, no `server.error` settings (Boot defaults hide traces; verified), no body size limit (F12). `schema.sql` checks (balance >= 0, amount > 0, status/decision consistency, unique indexes) all held.

## What I could not test
- NVD-based CVE scan (needs API key).
- The React UI in a browser (XSS rendering verified by code reading and by confirming the API returns payloads as JSON data only).
- TLS/HSTS, reverse proxy behaviour and anything outside plain HTTP on localhost.
- Rate-limit/lockout (none exists; I measured speed only: about 12 wrong logins/s sequential, about 100/s with 200 parallel, all 401, never throttled, each about 75 ms of BCrypt; no timing difference between unknown and enabled users).
- A real production configuration (`JWT_SECRET` env) was not run; tests used the `dev` profile.
- Very long-running or memory-exhaustion DoS beyond the 8 x 400 MB test.
- Attacks on the dev instance on 8080 (out of scope), including the LAN reachability in F1, which I inferred from `netstat` only.

## Cleanup
My 8081 instance (java PID 20232) was stopped. Port 8080 was verified still listening afterwards. `git status` shows no changes in the repo (scripts and outputs are in the scratchpad). `walletdb_test` was left with the test data; `walletdb` and `walletdb_old_backup_20261021` were never connected to.

## Status after round 2 (see SECURITY_FIXES_ROUND2_LOG.md)
| # | Status |
|---|---|
| F1 | Fixed earlier (`server.address=127.0.0.1` in the dev profile) |
| F2 | Fixed (NUL rejected with 400 by validation; SQLState 22021 also mapped to 400) |
| F3 | Fixed (password compared before the enabled flag) |
| F4 | Fixed (offset above int range gives 400 on all paged endpoints) |
| F5 | Fixed (dates limited to 1900-01-01 .. 2200-12-31, else 400) |
| F6 | Fixed (direct `/error` is 401/403, never 500) |
| F7 | Fixed (multipart disabled, 415) |
| F8 | Fixed (ASCII-only usernames; control/format/bidi characters rejected in notes and email) |
| F9 | Partly fixed (identical "Receiver not found"); existence via success/failure accepted as inherent |
| F10 | Fixed at login (over 72 bytes gives 401); library fix still comes with M5 |
| F11 | Open: only major upgrades fix the advisories; documented, `npm audit` unchanged |
| F12 | Unchanged (info) |
