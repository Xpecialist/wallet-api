# Security fixes, round 2 (branch `Testing`, nothing committed)

Source: SECURITY_TEST_REPORT.md (attack-style pass). F1 (dev profile bound to localhost) was already done and is not repeated.
Method: every finding was first reproduced with the new integration tests against an unmodified checkout of HEAD (a temporary
git worktree, since removed): 12 of the 15 `SecurityRound2IT` tests and 2 of the 3 `ContainerErrorIT` tests failed there with the
reported behaviour (500s, 201 for invisible characters, 401 vs 200 for 73-byte passwords, "Wallet not found" vs "Receiver not found",
disabled-account timing). The tests that passed on HEAD are regression guards (ordinary names, legacy names, NUL in email/password,
real container errors keep their status). Then the fixes were applied. Only `walletdb_test` was used (TestDbGuard unchanged);
`walletdb`, `walletdb_old_backup_20261021` and ports 8080/5173/8081 were never touched. The ITs that need real Tomcat start it
on a random port.

## Decisions
- Usernames: ASCII letters, digits and `. _ -` only (3-50 after trimming surrounding plain spaces). ASCII-only also removes the
  Cyrillic/fullwidth look-alike class (the known L1 residual), at the cost of non-English names. Case-insensitivity (L1) and the
  reserved `admin` protection are unchanged.
- Notes (transfer, deposit request, decision note): no control characters (so no newline or tab; the UI fields are single line),
  no Unicode format characters (Cf: zero-width, soft hyphen, bidi overrides), no line/paragraph separators, no unpaired surrogates.
  Plain spaces and all ordinary Unicode text (accents, CJK, emoji) are allowed. Side effect: emoji sequences joined by the
  zero-width joiner (family emoji) are refused.
- Existing data is never rewritten and no schema change was made. Login and the transfer `receiverUsername` only reject NUL (which
  the database cannot hold); every other odd name simply does not match (401/404). So an account that already exists with an odd
  legacy name (registered before this fix) can still log in and receive transfers. Tested.

## Findings

### F2 NUL character gives 500: CONFIRMED, fixed
- Cause: PostgreSQL rejects 0x00 (SQLState 22021); `GlobalExceptionHandler.integrity` returned 500 for every state except 23505/22003/22001.
- New bean-validation constraints in `dto/`: `@PlainText`, `@NoNul`, `@Username` (shared rules in `TextRules`). Applied to: register `username` (`@Username`) and `email` (`@PlainText`); profile `email` (`@PlainText`); login `username` and transfer `receiverUsername` (`@NoNul`); transfer `note`, deposit-request `note`, `decisionNote` (`@PlainText`). They fail with 400 `field: message` through the existing `MethodArgumentNotValidException` handler.
- Safety net for anything that still reaches the DB (for example the admin `username` query parameter): `integrity` maps 22021 to 400 "Input contains an invalid character" (before: 500).
- Tests: `SecurityRound2IT` (username register/login, every note and receiver field, decision notes on approve and reject with the request staying PENDING, `?username=a%00b` query parameter, profile email, NUL in passwords never 5xx), `DtoValidationTest`, `GlobalExceptionHandlerTest.invalidByteSequence...`.
- Remaining risk: a NUL inside a JWT `sub` would still reach `findByUsername` in `JwtAuthFilter` (needs the signing key, so not attacker reachable). Not changed.

### F8 invisible/control/bidi/look-alike characters: CONFIRMED, fixed
- Before: zero-width space, soft hyphen, newline, Cyrillic and fullwidth names all registered (201); bidi override and newline stored in notes; profile email with a zero-width character accepted (200).
- After: usernames and notes as in "Decisions"; email also gets `@PlainText` on top of `@Email`.
- Files: `dto/TextRules`, `PlainText`, `NoNul`, `Username`, `RegisterRequest`, `UpdateProfileRequest`, `LoginRequest`, `TransferRequest`, `CreateDepositRequest`, `DepositDecisionRequest`.
- Tests: `SecurityRound2IT` (14 bad usernames, ordinary names and case-insensitive duplicates, reserved `admin`/`ADMIN`/` Admin `/`aDmIn` still 409, Turkish `ADMİN` and dotless `admın` now 400, notes with newline/tab/CR/bidi/ZWSP/soft hyphen/U+2028 rejected on transfer and deposit request while "Café 日本語 ref #1" is accepted, legacy odd-name login and transfer), `DtoValidationTest` (parameterized).
- Frontend: the register page declares the rule (label "Username (3-50: letters, digits, . _ -)", `pattern`/`title` on the input, passed through `AuthPage`). Test in `auth.test.jsx`. Notes get no client pattern (single-line inputs cannot hold a newline; the server message is shown for the rest).
- Remaining risk: other Unicode look-alikes are now impossible for usernames, but notes and emails can still contain visually confusing ordinary letters (not a login or routing identifier). Existing accounts with odd names stay as they are (an operator can find them with a query on `users.username !~ '^[A-Za-z0-9._-]+$'`).

### F3 disabled-account timing oracle: CONFIRMED, fixed
- Cause: `DaoAuthenticationProvider` runs its pre-check (enabled flag) before the BCrypt comparison, so wrong password on a disabled user returned in ~2 ms against ~75 ms.
- `SecurityConfig`: new `DaoAuthenticationProvider` bean with empty pre-authentication checks and a post-check that throws `DisabledException` after the password was compared. Same 401 body for wrong password, disabled + wrong password and disabled + right password.
- Tests: `SecurityConfigProviderTest` (encoder is called for a disabled user; right/wrong password outcomes), `SecurityRound2IT.wrongPasswordOnADisabledAccountTakesAsLongAsOnAnEnabledOne` (average over 4 logins each must be above half of the enabled time; at HEAD it failed). Existing M2 tests still pass.
- Remaining risk: timing tests on a loaded machine could in theory flake (the margin is large: ~2 ms against ~75 ms). Unknown username vs known username was already equal (Spring's dummy hash).

### F4 huge `page` gives 500: CONFIRMED, fixed
- Cause: `page * size` overflowed the int offset (`page=99999999&size=100`, `page=2147483647`, `page=1073741824&size=2`).
- New `service/RequestLimits.checkPage`: if `pageable.getOffset() > Integer.MAX_VALUE` then 400 "page is too large". Called next to `SortWhitelist.check` in all five paged services: `TransactionService.history/listAll`, `UserService.listAll`, `DepositRequestService.listOwn/listAll`. Pages inside the limit (even empty ones) still return 200.
- Tests: `RequestLimitsTest`, `SecurityRound2IT.hugePageNumbers...` (all five endpoints, four overflow shapes each, plus `page=100000` returning an empty 200).
- Remaining risk: none known; deep pages are still a slow-query vector (offset scans), which belongs with rate limiting (M1, out of scope).

### F5 (=L8) extreme dates give 500: CONFIRMED, fixed
- Cause: `to.plusDays(1)` overflows at `LocalDate.MAX`; years beyond what the DB timestamp holds also fail.
- `RequestLimits.checkDates`: `from` and `to` must be within 1900-01-01 .. 2200-12-31, otherwise 400 "from|to must be between 1900-01-01 and 2200-12-31". Called in `TransactionService.history/listAll`. (Values the date parser itself rejects keep Spring's own 400.)
- Tests: `RequestLimitsTest`, `SecurityRound2IT.datesOutside...` (`+999999999-12-31`, `-999999999-01-01`, 1899-12-31, 2201-01-01, 9999-12-31, `+10000-01-01` on both history endpoints; the boundary dates 1900-01-01 and 2200-12-31 give 200).
- Remaining risk: the Spring conversion error text for unparsable dates still names `java.time.LocalDate` (F12 info item, not in scope).

### F6 `GET /error` gives 500, status 999: CONFIRMED, fixed
- Cause: `/error` was `permitAll` for every dispatcher type, so a direct call reached Boot's error controller, which has no error attributes (status 999).
- `SecurityConfig`: `dispatcherTypeMatchers(ERROR).permitAll()` (the container's error forwards, which is why `/error` had to be public) plus `requestMatchers("/error").denyAll()` for direct requests. Result: anonymous 401, authenticated 403, never 500.
- Tests: `ContainerErrorIT` over real Tomcat (direct GET anonymous/authenticated, POST/PUT/DELETE; and a check that real errors keep their status: unknown path 404, USER on admin 403, anonymous 401, malformed JSON 400, wrong method 405). Both failed at HEAD for the right reason (500).
- Remaining risk: asked behaviour was "404 or the original status"; direct calls give 401/403 like any other unauthenticated or forbidden path, not 404.

### F7 malformed multipart gives 500: CONFIRMED, fixed
- Cause: Spring parsed the multipart body (Tomcat `MalformedStreamException`) before the controller; no handler mapped it.
- `application.properties`: `spring.servlet.multipart.enabled=false` (the API has no uploads). A multipart body now gets the normal 415 from the JSON endpoints. No exception handler was added, since with multipart off that code path no longer exists.
- Tests: `ContainerErrorIT.malformedOrUnexpectedContentTypes...`: 7 POST endpoints (login, register, transfer, withdraw, deposit request, admin reject, admin deposit) x 4 malformed multipart bodies, plus `text/plain`, `application/xml`, `application/x-www-form-urlencoded`, `multipart/form-data` without or with an empty boundary: all 415, no exception text in the body.
- Remaining risk: none known. No request body size limit exists (F12, unchanged).

### F9 wallet-id / username existence oracle on transfer: CONFIRMED, partly fixed, rest accepted
- Fixed (cheap): unknown receiver by username and by wallet id now return the identical 404 `{"timestamp","status","error":"Receiver not found"}`; before, the wallet id path said "Receiver wallet not found" (or "Wallet not found" depending on whether the receiver id was lower or higher than the sender's). `TransactionService.transfer`.
- Accepted: a sender must learn whether the receiver exists (a transfer either succeeds or reports it), so "success or 422 vs 404" still reveals existence; the success response also maps username to wallet id. Removing that would break the feature. Mitigation is rate limiting (M1, out of scope); registration 409s reveal usernames anyway.
- Tests: `SecurityRound2IT.unknownReceiverByUsernameOrByWalletId...` (name, id 0, id 987654: same status, message, body shape; balance untouched).

### F10 BCrypt 72-byte prefix match at login: CONFIRMED, fixed
- Cause: Spring Security 6.3.4 truncates to 72 bytes when matching. Only users with a 72-byte password could be hit, and only by someone who already knows those 72 bytes.
- `AuthService.login`: a password over 72 UTF-8 bytes throws `BadCredentialsException` (same 401 and body as any bad credential; the authentication manager is never called, nothing truncated). Registration and change-password limits are unchanged. Small addition in the same spirit: `ChangePasswordRequest.currentPassword` also gets `@MaxUtf8Bytes(72)` (400, the status of a wrong current password anyway), otherwise the same prefix match applied there.
- Tests: `AuthServiceTest` (73 ASCII bytes, 37 two-byte chars rejected without calling the manager; exactly 72 bytes OK), `DtoValidationTest`, `SecurityRound2IT.loginNeverMatchesALongPasswordByItsFirst72Bytes` (72 `a` registers and logs in, 73/tail/200 chars give 401, multi-byte 72-byte password works and 74 bytes gives 401, register and change-password still 400).
- Remaining risk: an account created before round 1 (L3) with a password over 72 bytes but under 72 characters was hashed from its 72-byte prefix and can no longer log in with the full password (it must use the first 72 bytes or reset the password; there is no reset flow). Also M5 (Spring Boot upgrade) remains the proper fix for the library behaviour.

### F11 frontend `npm audit`: CONFIRMED, NOT changed (no non-major fix exists)
Checked with `npm audit`, `npm audit --omit=dev`, `npm ls` and `npm view <pkg> versions`.
| Advisory | Installed | Newest in same major | What fixes it | Decision |
|---|---|---|---|---|
| react-router / react-router-dom (moderate, runtime): open redirect via backslash in `<Link>`/`useNavigate`; SSR hydration constructor injection | 6.30.6 | 6.30.6 (already the latest 6.x) | react-router-dom 7.18.4 (major) | Not applied. Exposure is low: every `to=`/`navigate()` target in `frontend/src` is a fixed string (no user data), and the app is client-side only (no SSR hydration). |
| vite (high in the first report, now grouped under esbuild; dev server path traversal / `server.fs.deny` on Windows) | 5.4.21 | 5.4.21 | vite 6.4.4+ / 8.x (major) | Not applied (major). Dev server only, not in the production bundle. Bound to localhost by default; do not expose `npm run dev` to a network. |
| esbuild <=0.24.2 (moderate): any website can query the dev server | 0.21.5 (via vite 5) | n/a (pinned by vite) | comes with the vite major | Not applied. Dev only. |
| vitest + @vitest/mocker (moderate), vite-node | 2.1.9 / 2.1.9 / 2.1.9 | 2.1.9 | vitest 5.0.5 (major) | Not applied (major). Test tooling; do not run the Vitest UI/browser server on a shared network. |
| tinypool <=2.1.1 (critical): prototype-pollution gadget to RCE through worker options | 1.1.1 | 1.1.1 | vitest 5.0.5 (major, pulls tinypool 2) | Not applied (major). Only reachable if untrusted input reaches the test runner's worker options. |
Final `npm audit`: 8 vulnerabilities (5 moderate, 1 high, 2 critical), unchanged; `npm audit --omit=dev`: 2 moderate (react-router only). The npm "fix available" entries all say `--force` / breaking change. Suggested follow-up task: move to vite 6+/vitest 3+ (then 5) together with react-router 7 as one planned upgrade with the frontend tests as the safety net. `package.json` and `package-lock.json` were not modified.

## Results
| Command | Result |
|---|---|
| `mvn verify` | BUILD SUCCESS. Unit (surefire) 221 tests, 0 failures/errors/skipped (was 174). Integration (failsafe, `walletdb_test`) 151 tests, 0 failures/errors/skipped (was 133; +15 `SecurityRound2IT`, +3 `ContainerErrorIT`) |
| `cd frontend && npm test` | 8 files, 218 tests passed (was 217; +1 username rule test) |
| `cd frontend && npm run build` | success |

| Finding | Status |
|---|---|
| F1 | already fixed before this round |
| F2 | fixed |
| F3 | fixed |
| F4 | fixed |
| F5 | fixed |
| F6 | fixed (401/403 instead of 404) |
| F7 | fixed |
| F8 | fixed (ASCII-only usernames) |
| F9 | partly fixed (uniform message), rest accepted |
| F10 | fixed (library behaviour itself remains until M5) |
| F11 | open: only major upgrades fix it; documented, risk judged low |

## Deliberately not done
- Out of scope as instructed: M1 rate limiting, M5 Spring Boot upgrade, M6 Flyway, M7 DB credentials, L4 token revocation, deposit-request self-approval/expiry/cancel, simultaneous-replay 409.
- No database migration or rewrite of existing usernames/notes; no major frontend dependency upgrade.
- F12 info items (lenient JSON coercion, no body size limit, class names in conversion errors) untouched.
- No commit, no push. README got short notes on the new rules; `SECURITY_TEST_REPORT.md` and `TEST_FINDINGS.md` got a status section.
