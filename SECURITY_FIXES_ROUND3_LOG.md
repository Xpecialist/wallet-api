# Security fixes, round 3 (branch `Testing`, nothing committed)

Source: `SECOND_CODE_REVIEW.md` and `SECURITY_TEST_REPORT_2.md`. Groups 1 and 2 (findings A to F below). The third group
(IPv6 /64 keying, seed script, idempotency-key characters, `receiverWalletId` coercion, transfers to disabled users,
CSP/headers, DB credentials, admin self-approval/expiry/cancel, N+1, PII in logs, weak `JWT_SECRET` stack trace) was NOT
touched.

Method: every finding was first reproduced by a failing test against an unmodified checkout of HEAD 9806ff2 (a temporary
git worktree, since removed; the new tests that compile against the old API were copied into it). 4 test classes, 64 tests
were run there: 41 failed with the reported behaviour (11 of the 12 `FlywayMigrationIT` failures are the expected 2 -> 3
migration count and the missing V3 constraints), the others are regression guards that passed on HEAD (named per finding).
A fifth, separate test (`LockoutReproIT`, default limits) reproduced R3 on HEAD. Then the fixes were made. Only `walletdb_test` was used; `walletdb` and `walletdb_old_backup_20261021` were never
connected to, ports 8080/5173 were not used (the real-Tomcat tests use random ports; no app was started on 8081). The dev
backend on 8080 runs from `target/classes`, which `mvn` recompiled (no `mvn clean`); it still runs the OLD classes until
it is restarted, and its next start applies V3 to `walletdb` (see E).

## A. Rate-limit bypass by percent-encoded path (H-1 / R1): CONFIRMED, fixed

- Reproduced on HEAD over real Tomcat (`java.net.http.HttpClient` with the path written as-is): `/%61pi/auth/login`,
  `/ap%69/auth/login`, `/api/auth/%6cogin` and `/%61%70%69/auth/%6c%6f%67%69%6e` all returned 401 nine times in a row (the 9th
  must be 429); 4/4 registrations through `/%61pi/auth/register` (cap 3) and 6/6 money requests (cap 4), 5/5 password
  changes (cap 3), 5/5 admin deposits (cap 4), and the 61st anonymous request of the global bucket was 401 not 429.
- Root cause: both filters had `shouldNotFilter = !getRequestURI().startsWith("/api/")` on the RAW uri, while Spring MVC and
  Spring Security match the decoded path.
- Fix: no path gate at all. `RateLimitFilter` and `UserRateLimitFilter` skip only when `app.rate-limit.enabled=false`; every
  request counts in the global per-IP bucket; the login/register/password/money/admin rules are `PathPatternRequestMatcher`s
  (the matcher family and decoded-path semantics of Spring MVC and `authorizeHttpRequests`), so `/%61pi/auth/login` IS the
  login endpoint for the limiter. The new body-size filter uses the same matcher (F).
- Variants checked (real Tomcat): `;x=1` (path parameter), `//`, `/api//`, dot segments (`/./`, `/../`, `/x/../api/...`),
  encoded dots/slashes/NUL, trailing slash, other case: every one is rejected before the controller (StrictHttpFirewall 400 or
  router 404/401), none can log in; none needs the limiter. The raw uri is not normalized by Tomcat, so the old report's
  claim that dot segments were "still limited" is not what happens: they get 400 from the firewall. HEAD to the login path is
  405; OPTIONS answers the Allow list (200) without running the handler; both count in the global bucket now.
- Admin authorization: `/ap%69/admin/users`, `/%61pi/admin/users`, `/api/admin/%75sers`, `/%61pi/admin/transactions`,
  `/%61pi/admin/deposit-requests` give 403 for a USER, 401 anonymous, 200 for ADMIN; admin deposit and PATCH status as USER 403.
  NOT a finding: this test passed on unmodified HEAD (the authorization matcher and MVC already agreed; only the limiter
  was wrong).
- Tests: `RateLimitRealHttpIT` (new, real Tomcat on a random port): every encoded spelling of login limited per IP,
  encoded and plain share the username bucket, register, money (transfer and withdraw), password change, admin deposit,
  global bucket counting encoded paths, admin paths 403/401/200, odd spellings never reach the login controller,
  HEAD/OPTIONS, filter order; `RateLimitFilterTest` (decoded-path matching and no gate, with MockHttpServletRequest).
- Remaining risk: the limiter trusts the same path semantics as Spring; a future change of the MVC path matching
  (for example enabling trailing-slash matching) would have to be mirrored, but the matchers are `PathPattern`-based like
  MVC's.

## B. Per-username login bucket bypass by body padding / non-string username (M-1 / R2): CONFIRMED, fixed

- Reproduced on HEAD: a 9000-byte body (`pad` field) gave 401 for 8 of 20 attempts for one username from one IP in a minute
  (limit 5); sizes 8193/9000/15000 gave 401 not 413; `{"username":123}` gave 401 (the controller coerces it to "123");
  duplicate `username` keys were accepted (the last one wins in both, so no mismatch, but unspecified); a body sent with
  `charset=ISO-8859-1` was accepted (200).
- Root cause: `usernameOf` returned null (no bucket) for bodies over 8192 bytes, for non-string usernames and for anything
  unparsable, and the controller then interpreted the body differently or at all.
- Fix (`RateLimitFilter.login`, fails closed before any password is checked, after the per-IP buckets so probing costs tokens):
  declared `Content-Length` over 8192 or a body that is still going after 8192 bytes: 413; content type not JSON or a
  charset other than UTF-8: 415; not valid JSON, not an object, duplicate keys (any key), trailing content, or `username`
  missing / not a string / blank after `trim()`: 400. The username key is `trim()` + `toLowerCase(Locale.ROOT)` of the string
  exactly as `AuthService.login` trims it; the filter parses with the application's own `JsonMapper` rebuilt with
  `STRICT_DUPLICATE_DETECTION` and `FAIL_ON_TRAILING_TOKENS`, so it cannot disagree with Jackson in the controller (duplicates are
  rejected rather than "last wins"). Read-ahead is at most 8193 bytes; the controller gets the same bytes replayed
  (`ReplayableRequest`, now a shared top-level class).
- Tests: `RateLimitRealHttpIT` (real Tomcat: exact 8192 passes, 8193/9000/15000 and chunked 413 JSON shape, padded guesses at
  most 5 password checks, non-string/blank/missing username list 400, empty/array/malformed bodies, duplicate keys,
  charset list 415 and UTF-8 variants 200, BOM and UTF-16 bodies never exceed 5 password checks, chunked and case/whitespace
  variants in one bucket), `RateLimitFilterTest` (the same at unit level incl. trailing content).
- Passed on HEAD (regression guards): BOM / UTF-16 bodies and chunked / case-variant bodies already shared the bucket; a normal login works.
- Remaining risk: usernames in the legacy database that contain non-ASCII letters are lower-cased by Java (`Locale.ROOT`)
  for the bucket key but by PostgreSQL `lower()` for the lookup; both are only used for the same account's bucket, and
  registration is ASCII-only, so a mismatch could only make two spellings of a legacy name share or not share a bucket.
  Not verified against real legacy data.

## C. Lost update on `User` (M-2 / R4): CONFIRMED, fixed

- Reproduced on HEAD (`UserUpdateRaceIT`, 6 of 8 tests failed): a password change racing an admin disable ended ENABLED
  (iteration 1 of 25: "the change must not re-enable the user", expected false was true), an email change racing a disable
  ended enabled, a stale entity saved after a disable re-enabled the user and restored the old hash, a password change
  racing a logout returned a token that still works (R4: expected 401 but was 200), and parallel password changes with
  one token had several winners.
- Root cause: `UserService` loaded a `User` and `save()`d it whole row: Hibernate writes every updatable column, so
  `enabled`, `password_hash` and `email` were overwritten from a stale snapshot (the BCrypt work between load and flush
  is the window); and `changePassword` re-read the version after its own bump without checking the caller's.
- Design: targeted statements, as for `token_version`. `User.email`, `passwordHash` and `enabled` are `updatable = false`
  (so ANY future stale `save()` cannot clobber them, proven by a test), and `UserRepository` has one native UPDATE per
  operation: `changePasswordIfCurrent(id, tv, hash)` = `set password_hash, token_version = token_version + 1 where id = ?
  and token_version = :tv and enabled = true`; `updateEmail`; `enable`; `disableAndRevoke` = `set enabled = false,
  token_version = token_version + 1` in ONE statement. `@DynamicUpdate` + `@Version` was rejected: a version column would turn
  every disable-vs-profile race into a 409/retry, and `updatable=false` plus single-purpose UPDATEs prove each column has one writer.
- `changePassword(username, tokenVersion, req)`: the controller passes the version of the authenticated `AuthUser` (the one the
  JWT filter compared with the token); the service rejects 401 early if it is stale or the account disabled (no BCrypt),
  verifies the password (400 as before), then runs the conditional UPDATE; 0 rows (revoked, disabled or lost the race) is 401
  and no token. The fresh token is for `tokenVersion + 1`, which the statement guarantees (row lock held until commit), so the
  extra `findTokenVersion` read is gone. `setEnabled` re-reads the user after the UPDATE for the response. `bumpTokenVersion`
  (logout) is unchanged and atomic.
- Tests: `UserUpdateRaceIT` (new, 8): stale entity save cannot re-enable/overwrite; password change vs disable 25 iterations
  (always disabled, returned token rejected, exact version = 1 or 2, login 401); email change vs disable 25 iterations;
  disable vs concurrent password change keeps the new password; password change vs logout 25 iterations (a returned token is
  always dead); a revoked token that passed the filter cannot finish a change (service level, 401, password unchanged);
  6 parallel changes with one token over 5 rounds: exactly one 200, the rest 401, one bump; 6 logouts + disable + change
  lose no bump. `UserServiceTest` (conditional update, stale version, disabled, lost race at DB, no `save`), `ApiWebLayerTest`.
- Passed on HEAD (guard): 6 logouts + a disable + a password change lose no bump.
- Not done: the email change does not check the caller's token version (a revoked token that passed the filter could still
  finish ONE email change; no token is issued by it). The disable-versus-email race itself is fixed.

## D. Login lockout design (M-3 / R3): CONFIRMED, redesigned

- Reproduced on HEAD with default limits (`LockoutReproIT`, a temporary test in the worktree): 5 wrong attempts for `alice`
  from one address, then the real user with the right password from ANOTHER address: expected 200, was 429. The other
  `RateLimitIT` login tests that failed on HEAD fail because they assert the NEW contract (their test limits use the new
  property names, which HEAD ignores), not because they reproduce more. The documentation claim "never a lockout" is wrong and is
  corrected in `README.md` and `RATE_LIMITING_LOG.md`.
- Design chosen (the suggested one, details as built):
  1. `login-username-ip`: bucket per (normalized username, IP), every attempt counts, 5/min. Replaces the old shared
     `login-username` bucket. An attacker address can only exhaust ITS OWN bucket.
  2. `login-username-failures`: bucket per normalized username across all IPs, 30/min, counting FAILED attempts (HTTP 401) only.
     Admission: an address NOT in `KnownLogins` for that user takes a token first (429 with Retry-After if none); after the
     response the token is given back (`TokenBucketLimiter.refund`) unless the status was 401. A success (2xx) also records
     (username, IP) in `KnownLogins`. An address in `KnownLogins` is never blocked by this bucket; its own failures still
     take tokens (so a victim's typos count) but cannot block it. Reserving at admission, instead of counting after the
     fact, keeps parallel guesses from overshooting (tested: 24 parallel guesses from 24 IPs, exactly 8 pass at limit 8).
  3. `KnownLogins`: bounded insertion-ordered map of (username, IP) -> time of last success; TTL `known-login-ttl` 7 days,
     at most `known-login-max` 10000 (oldest success evicted first). Entries are added only after a successful
     authentication, so a flood cannot fill it without owning accounts. Per JVM, in RAM.
  4. Kept: per-IP login limit, global bucket, memory bounds (`max-keys` per limiter), no existence leak (unknown names are
     counted by the same code on the same events; tested identical sequences).
- Honest trade-off (also in the README):
  - An attacker can no longer lock a user out from ONE address, and a user logging in from an address they have used within
    7 days is never affected by anyone else's guessing.
  - Guess rate per account is HIGHER than before for a distributed attacker: up to 30 failed guesses/min per account across
    all addresses (burst 30, then one per 2 s), about 43,000/day, versus 5/min (7,200/day) before. Each address is still
    held to 5/min, so reaching 30/min needs at least 6 addresses. An attacker who shares an address with the user (NAT)
    gets up to 5/min more through that known address.
  - A login from a NEW address (new device or network, first login after 7 days, or after a server restart) is answered 429
    with a short Retry-After while a distributed attack of 30+ failures/min is running against that account. It resumes by
    itself once failures drop below one per 2 s. Nothing is permanent. An attacker with 6+ addresses can therefore still
    DELAY first-time logins of a chosen user (not users with a known address).
  - IPv6 /64 rotation, NAT sharing and the single-instance memory are unchanged weaknesses (listed, out of scope).
- Properties (defaults in the table below). The old `login-username.*` properties were replaced; Spring ignores unknown
  names, so an old override silently stops working: remove it.
- Tests: `RateLimitIT` (rewritten login section, 20 tests): attacker exhausting its own bucket does not block the victim's IP;
  failures spread over IPs are capped at the aggregate and a new IP gets 429 with Retry-After; distributed attack blocks new
  IPs (right password too) but not the known IP, and an IP known for another user does not help; successes and validation
  errors (400) do not use the aggregate; refill admits a new IP again; 24 parallel guesses, exactly the limit pass; case and
  whitespace variants share the (user, IP) bucket; unknown username identical to known in every status/body/Retry-After.
  `RateLimitFilterTest` (same at unit level), `KnownLoginsTest` (TTL, renewal, size bound under a 10,000-key flood, oldest
  evicted), `TokenBucketLimiterTest.refund`.

## E. Missing CHECK constraints (R5 / L-5): CONFIRMED, fixed

- Reproduced on HEAD: `v3ConstraintsReject...` failed on the first 'DONE' insert (accepted); the same for role 'SUPER', currency
  'usd' and a self transfer.
- `V3__more_constraints.sql` (V1 and V2 untouched): `chk_tx_status` (PENDING, COMPLETED, FAILED = `TransactionStatus`),
  `chk_users_role` (USER, ADMIN = `Role`), `chk_wallets_currency` (`~ '^[A-Z]{3}$'`; the app default and every wallet written by
  the app is USD; a lower-case `app.wallet.currency` would now fail wallet creation with a database error), and
  `chk_tx_no_self_transfer` (both ids null-safe: only compared when both are set). `transactions.type` already had an implied
  CHECK (`chk_tx_type_wallets` accepts only TRANSFER/DEPOSIT/WITHDRAWAL), so no new one.
- Plain `ADD CONSTRAINT`, validated: PostgreSQL DDL is transactional, so a database that holds a violating row fails the
  migration with the constraint name and nothing is changed (tested: V2 database with a 'DONE' row stays at V2). Each statement scans
  its table once under a short exclusive lock; fine at this size, use `NOT VALID` + `VALIDATE` for a huge `transactions` table.
- `SchemaShapeGuard.verifyCurrent` now also requires the four constraints (`verify`, the pre-baseline V1 check, does not).
  Hibernate `validate` passes (the V2 -> V3 test starts the application); enums are `EnumType.STRING`, no conflict.
- Existing rows: the pre-existing contents of `walletdb_test` (the second attack pass' data) were wiped by the first integration
  test run (TestDbGuard cleans the database) before I could query them, so that check was not done; the V2 -> V3 upgrade
  test with data covers the migration. The dev `walletdb` was NOT opened (rule). Check it before the first start with the new
  code (read-only):
  `select count(*) from transactions where status not in ('PENDING','COMPLETED','FAILED'); select count(*) from users where role not in ('USER','ADMIN'); select count(*) from wallets where currency !~ '^[A-Z]{3}$'; select count(*) from transactions where sender_wallet_id = receiver_wallet_id;` (all must be 0).
- Tests: `FlywayMigrationIT` now expects 3 migrations everywhere; new: V2 database with data migrates to V3 keeping everything
  and Hibernate validate passes; V3 refuses violating data and leaves V2; direct-SQL rejection of 'DONE'/'completed'/''/'SETTLED',
  'SUPER'/'admin'/'', 'usd'/'Usd'/'US'/'U$D'/'12A'/' US'/'', self transfers (insert and update), with valid values still accepted;
  `verifyCurrent` names a dropped V3 constraint.

## F. Body size cap and username length (L-4): CONFIRMED, fixed

- Reproduced on HEAD: a 20,000 character note on transfer gave 400 not 413 (Jackson parsed it first); a 20,000-byte
  chunked body on register/transfer and `PATCH /api/users/me` likewise; the `exact + " "` over-the-cap body gave 400.
- `BodyLimitFilter` (new): bodies under `/api/**` (decoded-path `PathPatternRequestMatcher`, so `/%61pi/` is covered) over
  `app.max-body-bytes` (default 65536) get 413 in the JSON shape. Declared `Content-Length` over the cap: refused without
  reading; no length (chunked): read at most cap+1 bytes, refused if more; within the cap the body is buffered and replayed. Placed in the
  security chain right after `RateLimitFilter` and before `JwtAuthFilter` (a test asserts the order), so the global per-IP
  bucket also counts oversized requests, and the 413 happens before authentication and JSON parsing.
- `@Size(max = 50)` on `LoginRequest.username` and `TransferRequest.receiverUsername` (registration allows at most 50 after
  trimming; legacy usernames are `VARCHAR(50)`, so no existing login breaks; a raw value of 50 characters plus surrounding
  spaces is over 50 and is now 400, the same rule as registration). The admin deposit-request `username` filter over 50 characters is 400.
  `DepositRequestService` uses `toLowerCase(Locale.ROOT)` (it was the only default-locale case conversion in `src/main`;
  `grep` finds none left); tested under a Turkish default locale.
- Tests: `RateLimitRealHttpIT` (declared and chunked 413 JSON shape on authenticated transfer, unauthenticated encoded
  register, PATCH; exactly at the cap handled normally, one byte over 413; non-API paths not capped), `BodyLimitFilterTest`,
  `DtoValidationTest`, `DepositRequestServiceTest`, `DepositRequestIT` (locale and bound).
- Passed on HEAD (guard): non-API paths are not capped.
- Frontend: not changed (npm test 269 passed, not rebuilt). Errors use the server's `error` text already; no 413-specific message.

## New and changed properties

| Property | Default | Meaning |
|---|---|---|
| `app.rate-limit.login-username-ip.capacity` / `.window` | 5 / 1m | per (username, IP), every login attempt (replaces `login-username.*`) |
| `app.rate-limit.login-username-failures.capacity` / `.window` | 30 / 1m | per username across IPs, failed attempts only, not applied to known addresses |
| `app.rate-limit.known-login-ttl` | 7d | how long a successful login keeps (username, IP) known |
| `app.rate-limit.known-login-max` | 10000 | most remembered (username, IP) pairs (oldest success evicted) |
| `app.max-body-bytes` | 65536 | largest request body under `/api/**` (413 above); login body is separately capped at 8192 |
| removed: `app.rate-limit.login-username.capacity` / `.window` | | |

## Files changed

Main: `security/RateLimitFilter` (rewritten: no path gate, strict login body handling, aggregate logic), `UserRateLimitFilter`
(no path gate), `RateLimits` (new limiters, `error()`), `RateLimitProperties`, `TokenBucketLimiter` (+`refund`), `KnownLogins`
(new), `BodyLimitFilter` (new), `ReplayableRequest` (new, extracted), `ApiErrors` (new), `SecurityConfig` (filter order, body cap
property); `entity/User` (`updatable=false`), `repository/UserRepository` (4 targeted UPDATEs), `service/UserService`
(`changePassword(username, tokenVersion, req)`, `updateEmail`, `setEnabled`), `controller/UserController`;
`dto/LoginRequest`, `dto/TransferRequest` (`@Size`); `service/DepositRequestService` (Locale.ROOT, username bound);
`config/SchemaShapeGuard`; `db/migration/V3__more_constraints.sql`; `application.properties`. Docs: `README.md`,
`RATE_LIMITING_LOG.md` (header note and two corrected statements), this file.

## Results

| Command | Result |
|---|---|
| `mvn test` | 293 unit tests, 0 failures (was 270) |
| `mvn verify` | BUILD SUCCESS: 293 unit + 251 integration, 0 failures (was 270 + 211; new IT classes `RateLimitRealHttpIT` 22, `UserUpdateRaceIT` 8, extra tests in `FlywayMigrationIT`, `RateLimitIT`, `DepositRequestIT`) |
| Flakiness: 5 consecutive runs of `UserUpdateRaceIT`, `RateLimitIT`, `RateLimitRealHttpIT`, `RateLimitRefillIT`, `RateLimitTrustedProxyIT`, `TokenRevocationIT`, `ConcurrencyIT`, `IdempotencyReplayIT`, `DepositRequestConcurrencyIT` | 5 of 5 green, 103 tests each, no failure or retry |
| `cd frontend && npm test` | 269 passed (frontend not changed; `npm run build` not run) |
| Reproduction on unmodified HEAD (temporary worktree, removed) | 41 of 64 tests failed as described per finding; R3 reproduced by `LockoutReproIT` (200 expected, 429 got) |

The ERROR lines in the integration output (refused old database, JWT-secret and admin-bootstrap tests, expected constraint
violations) are expected, as in earlier rounds.

## What could not be verified / not done

- The dev `walletdb` was not opened: V3 has not been checked against its rows (query in E) and not applied. Its next
  backend start applies V3; take a backup first as for any migration. The dev backend on 8080 still runs the old classes
  and has the old behavior until restarted (the new login-limit property names, the body cap and V3 take effect then).
- No application was started on 8081 and nothing was tested through a browser or a real reverse proxy. IPv6 clients,
  multi-instance behavior and TLS were not tested. The real-Tomcat tests use `127.0.0.1` only (one client address), so the
  distributed-attack scenarios of D are covered with MockMvc remote addresses, not with real separate sockets.
- Memory use of the new structures was bounded and unit-tested (`KnownLogins`, `TokenBucketLimiter` size limits), not
  measured at `max-keys`. Buffering up to 64 KB per in-flight request body (`BodyLimitFilter`) under load was not measured.
- Timing-based tests (`RateLimitRefillIT`, the refill step in `RateLimitIT`) wait for real refills of 2 s and 8 s windows;
  green 5 of 5 here, could be slower on a heavily loaded machine.
- Not done on purpose (out of scope or judged too small): the email change does not re-check the token version (C); no
  frontend message for 413; `SchemaShapeGuard` still checks constraint names, not definitions (L-5 remainder);
  `chk_tx_type` was not added because `chk_tx_type_wallets` already restricts the type; the third-group items listed at the top.
