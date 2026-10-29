# Rate limiting (finding M1), 2026-10-23

> **Round 3 update (2026-10-29), read this first.** Four statements below are superseded; details in `SECURITY_FIXES_ROUND3_LOG.md`:
> 1. "Requests outside `/api/` are never limited" / the `startsWith("/api/")` gate: it tested the RAW URI, so `/%61pi/auth/login` skipped every limit. There is no path gate any more; endpoints are matched on the decoded path and every request counts in the global per-IP bucket.
> 2. The login body "over 8192 bytes gets no per-username bucket": such bodies are now refused with 413 (non-UTF-8 charsets 415; duplicate keys, trailing content, non-string or blank usernames 400).
> 3. The per-username bucket `login-username` (every attempt, shared by all IPs) is replaced by `login-username-ip` (5/min per username and IP, every attempt) and `login-username-failures` (30/min per username across IPs, FAILED attempts only, never applied to an address that logged in as that user within 7 days).
> 4. "slowed to about one attempt per 12 seconds, never a lockout" was wrong (see the corrected item under Remaining risks).

Branch `Testing`, nothing committed. Fixes M1 of `TEST_FINDINGS.md` (no brute-force protection; ~12 wrong logins/s sequential and ~100/s parallel against one account, unlimited registrations, per `SECURITY_TEST_REPORT.md`).

## Design and why

In-memory token buckets, applied by two servlet filters inside the Spring Security chain.

**Library vs self-written: self-written.** Bucket4j is not in the local Maven repository (it would be a new download, plus a second module for the Boot 4 / Jakarta combination to verify) and the need is small: one bucket per key with refill, a bounded map and a Retry-After value. `TokenBucketLimiter` is about 100 lines, uses exact integer arithmetic (no floating point drift), has an injectable clock and is unit-tested including concurrency. Caffeine is only on disk as a transitive of something else, not a project dependency; the bound/expiry logic needed here (LRU + idle expiry in one lock) is simpler as a `LinkedHashMap` in access order than as a new dependency. Trade-off: if the app ever needs distributed limits, Bucket4j + Redis is the natural next step and `TokenBucketLimiter.tryAcquire(key)` is the only seam to replace.

**Where the filters sit.**
- `RateLimitFilter` is added BEFORE `JwtAuthFilter`, i.e. first in the security chain (after Spring's firewall and context holder filters). It handles what needs no identity: global per-IP cap, login (per IP, per username), register (per IP). A rejected request costs no JWT parsing, no DB lookup and no BCrypt.
- `UserRateLimitFilter` is added right AFTER `JwtAuthFilter`. It handles password change (per user and per IP) and money endpoints (per user). It does nothing for an unauthenticated request (the security rules answer 401, which is cheap). Revoked or disabled users never reach it as authenticated, so their 401 is unchanged.
- Both are created with `new` in `SecurityConfig.filterChain`, not as beans, so Boot does not also register them as servlet filters (the same reason `JwtAuthFilter` has a disabled registration).
- `OncePerRequestFilter` skips ERROR and async dispatches, so the `/error` handling and the container error forward are untouched. Requests outside `/api/` are never limited.
- Path matching uses `PathPatternRequestMatcher` (the same matcher family as the authorization rules), after Spring's `StrictHttpFirewall` has rejected odd paths (`//`, `;`, encoded slashes).

**Login username.** The login body is JSON, so the filter reads it (only for `application/json` or `+json` content types, at most 8192 bytes; a valid login body is far smaller since username max 50 and password max 72) with the application's own `JsonMapper` bean, so the filter and the controller parse identically. The bytes are replayed to the controller through a request wrapper (also for oversized bodies, whose remainder is passed on untouched). The username is trimmed (as `AuthService.login` does) and lower-cased with `Locale.ROOT`; names longer than 50 characters (which cannot exist) share a single key so an attacker cannot make each request occupy a new map entry. A body that is not JSON, not an object, or has a non-string username gets no per-username bucket: it fails validation (400/415) before any password is checked. The per-IP bucket still counts it.

**Every attempt counts** (successful logins too). Counting only failures would need the result of the controller, would make the filter stateful across the response, and a success carries no information for an attacker anyway; with 5 per minute a real user is not affected. Windows are short and refill continuously: no permanent lockout. (SUPERSEDED in round 3 for the per-username bucket: it is now per (username, IP) for every attempt, plus a separate per-username bucket that counts only failed attempts and spares addresses the user has logged in from. The shared "every attempt" per-username bucket let one address deny a user's login indefinitely.)

**No existence leak.** The per-username bucket is applied to any username string the same way; unknown and known names produce identical status, body and Retry-After (tested by comparing both sequences).

**Order inside the login check.** global-ip, then login-ip, then login-username. A request rejected by an earlier bucket does not spend a token of a later one.

**Client IP / proxy decision.** Default: `request.getRemoteAddr()` (the TCP peer; cannot be forged). `X-Forwarded-For` is used only when `app.rate-limit.trust-forwarded-headers=true`. Then the entry `trusted-proxies` positions from the RIGHT is used (default 1 proxy: the last entry). Reason: each trusted proxy appends the address it saw the request come from, so the right-most entries are written by infrastructure you control and anything to the left may be client-supplied. Multiple header lines are joined. If the header has fewer entries than `trusted-proxies`, or the chosen entry is not address-like (hex digits, `.`, `:`, max 45 chars), the peer address is used (fail-safe: one shared strict bucket rather than a forgeable key). The app cannot tell whether the TCP peer really is a trusted proxy (no CIDR list), so the property must only be set when the app is reachable only through the proxy; this is documented in the README.

**Memory bounds.** Each of the 8 rules has its own map. A key idle for a full window is back to full capacity, so it is dropped (checked from the least recently used end on every call, cost proportional to the number removed). Hard cap `app.rate-limit.max-keys` (default 50000) per rule, least recently used evicted first. Rough worst case: 8 x 50000 x ~150 bytes = about 60 MB, reached only by an attack that also has to get past the global per-IP cap (300/min per address). Eviction can only forget a key (making that key more lenient), never block someone. An attacker flooding 50000 distinct keys quickly could evict a victim's bucket; that costs the attacker far more requests than the bucket is worth.

**Single instance.** Counters are in the JVM: N instances multiply the limits by N, a restart resets them. Needs Redis (or similar) for a multi-instance deployment.

## Default limits

| Rule (`app.rate-limit.<rule>.capacity` / `.window`) | Endpoint | Key | Default |
|---|---|---|---|
| `global-ip` | all `/api/**` | IP | 300 / 1m |
| `login-ip` | POST `/api/auth/login` | IP | 5 / 1m |
| `login-username` | POST `/api/auth/login` | normalized username | 5 / 1m |
| `register-ip` | POST `/api/auth/register` | IP | 5 / 1h |
| `password-user` | POST `/api/users/me/password` | user | 5 / 15m |
| `password-ip` | POST `/api/users/me/password` | IP | 5 / 15m |
| `money-user` | POST transfer, withdraw, `/api/deposit-requests` (shared) | user | 30 / 1m |
| `admin-user` | POST admin wallet deposit, deposit-request approve/reject | user | 120 / 1m |

Other properties: `app.rate-limit.enabled` (true), `max-keys` (50000), `trust-forwarded-headers` (false), `trusted-proxies` (1). 429 response: `Retry-After: <seconds>` and `{"timestamp","status":429,"error":"Too many requests, try again in N seconds"}`.

## Code changes

New (all under `src/main/java/com/example/wallet/security/`):
- `TokenBucketLimiter.java`: bucket per key, integer-exact refill, `tryAcquire(key)` returns 0 or Retry-After seconds, LRU map with `maxKeys`, idle expiry, injectable nano clock (package-private constructor), `size()`, `clear()`.
- `RateLimitProperties.java`: `@ConfigurationProperties("app.rate-limit")` with the defaults above.
- `RateLimits.java`: component holding the 8 limiters, `clientIp(request)`, `reject(response, seconds)` (429 + Retry-After + JSON), `clear()` (test helper), `enabled()`.
- `RateLimitFilter.java`: pre-authentication filter (global, login, register), body replay wrapper, `normalize(username)`.
- `UserRateLimitFilter.java`: post-JWT filter (password, money, admin).

Modified:
- `SecurityConfig.java`. Before: `filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter)` ended with `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class); return http.build();`. After: `filterChain(HttpSecurity, JwtAuthFilter, ObjectProvider<RateLimits>, JsonMapper)`; after the JWT line it adds `new RateLimitFilter(limits, mapper)` before `JwtAuthFilter` and `new UserRateLimitFilter(limits)` after it when `RateLimits` is available. `ObjectProvider` is used only so the existing `@WebMvcTest` slice (`ApiWebLayerTest`, which imports `SecurityConfig` but does not component-scan `RateLimits`) keeps working unchanged; in the real application the bean always exists (the master switch is `enabled`, checked in the filters). Javadoc updated.
- `src/main/resources/application.properties`: added the `app.rate-limit.*` defaults, with comments.
- `src/test/resources/application-it.properties`: `app.rate-limit.enabled=false` (existing suites do far more logins/registrations from one address than production limits allow), and `spring.datasource.hikari.minimum-idle=1` (see "Surprises").
- `frontend/src/api/client.js`: `ApiError` has a `retryAfter` field; `parseError` maps HTTP 429 to "Too many attempts. Try again in N second(s)." from the `Retry-After` header (whole seconds only), or "Too many attempts. Please wait a moment and try again." when the header is missing/invalid. All forms already print `error.message`, so login, register, profile (password/email), transfer, withdraw, deposits and admin forms get it. No retry loop, session untouched (only 401 signs out). Submit buttons are not disabled (kept simple).
- `README.md`: new "Rate limiting" section (rules, properties, 429, proxy caveat, limitations), 429 added to the status code list, status section updated.

## Tests added

Unit (22): `TokenBucketLimiterTest` (10: burst then reject with Retry-After, shrinking Retry-After, continuous refill with injectable clock, no drift from rejected attempts, key independence, minimum 1 s, idle expiry, bounded size with LRU eviction under a 1000-key flood, invalid configuration, 8 threads never exceed capacity), `RateLimitFilterTest` (12: peer IP by default and spoofed X-Forwarded-For ignored, right-most with trust, forged left entries ignored, trusted-proxy count, fallbacks, normalization and over-long names, body replay incl. oversized body, bucket shared across IPs and case variants, malformed/non-string usernames, disabled switch, 429 shape, non-API paths).

Integration on `walletdb_test` (21): `RateLimitIT` (15), `RateLimitRefillIT` (2), `RateLimitTrustedProxyIT` (3), `RateLimitDisabledIT` (1), helper `RateLimitBase`. Covered: 6th login 429 with Retry-After while another IP/username is fine; spread across 5 IPs still stopped per username; case/whitespace variants share a bucket; unknown username identical to known (status, body, Retry-After); 429 body shape; registration flood limited and nothing created; password change per user and per IP, unauthenticated stays 401; money endpoints share one bucket per user, blocked transfer moves nothing, reads not limited; admin deposit limit; spoofed X-Forwarded-For ignored by default; trusted-proxy mode (own bucket per client, forged left entries ignored, no header = proxy address); refill after waiting then a valid login works; global per-IP cap incl. unauthenticated requests; master switch off; normal usage unaffected; regression for 400 validation, malformed JSON 400, 415, disabled-user login indistinguishable from unknown (401 same body), revoked token 401, direct `/error` 401, successful login with padded mixed-case username.

Frontend (10): `client.test.js` (5: Retry-After seconds, singular, fallback for missing/invalid values, no headers object, no sign-out and no retry), new `ratelimit.test.jsx` (5: login, login without header, register, password change, transfer).

No existing test was changed or weakened.

## Results

| Command | Result |
|---|---|
| `mvn verify` (unit, then integration) | BUILD SUCCESS: 261 unit (was 239) + 195 integration (was 174), 0 failures |
| `mvn test` | the unit phase of the above (261) |
| `cd frontend && npm test` | 240 passed (was 218 per earlier logs, 230 per the task; 10 new) |
| `cd frontend && npm run build` | ok |
| Real-server check (port 8081, dev profile, `walletdb_test`, stopped afterwards) | 5 wrong logins 401 with a forged different X-Forwarded-For each, 6th and 7th 429 `Retry-After: 12` with the JSON body, as did a login with the right admin password; the request body reached the controller through real Tomcat |

Surprises: with 5 more cached Spring contexts the test run hit PostgreSQL "too many clients" (every cached context kept 20 idle pool connections). Fixed by `spring.datasource.hikari.minimum-idle=1` in the `it` profile only.

## Remaining risks

- Single instance only; restart resets counters; N instances multiply the limits.
- Shared IPs (NAT, offices, carrier-grade NAT) share the per-IP buckets: legitimate users there can be blocked together (login 5/min per IP is the tightest; raise it by property if needed). Behind a proxy without `trust-forwarded-headers` everyone shares ONE bucket (the proxy's address); configure it.
- **CORRECTED (round 3).** This item originally said the shared per-username bucket only slows an attacked account to "about one attempt per 12 seconds (never a lockout)". That was wrong: an attacker who keeps pace (about 6 requests a minute from ONE address) took every refilled token, so the real user, from any address, was answered 429 for as long as the attacker kept going, i.e. a denial of login for a chosen account (found in `SECOND_CODE_REVIEW.md` M-3 and `SECURITY_TEST_REPORT_2.md` R3). The design was replaced in round 3 (see the top of this file and `SECURITY_FIXES_ROUND3_LOG.md`).
- Distributed attacks: one guess per IP across many IPs is stopped only by the per-username bucket (5/min per account); credential stuffing across many different accounts from many IPs is not stopped, only the global per-IP cap applies.
- `trust-forwarded-headers=true` without a real proxy in front lets clients forge their address.
- The 429 path costs one login-body read (8 KB max) before authentication; the Tomcat max POST/header limits still apply.
- Registration per IP is 5/hour: a legitimate burst of sign-ups from one address (classroom, event) is blocked; adjust by property.
- Rate limiting does not replace the other open items (default DB credentials etc.).

## Not verified

- Behaviour behind a real reverse proxy (`trust-forwarded-headers=true` was tested with MockMvc remote addresses and headers, not with nginx/ELB).
- No load or multi-instance test; memory use at `max-keys` was estimated, not measured.
- Browser behaviour of the new messages (frontend tests use jsdom and a mocked fetch); real `fetch` header access to `Retry-After` is same-origin through the Vite proxy; for a cross-origin deployment the server would have to expose the header via CORS (`Access-Control-Expose-Headers`), otherwise the generic message is shown. There is no CORS configuration in this project.
- The dev backend on 8080 runs from `target/classes`, which `mvn verify` recompiled; it was not restarted and still runs the old classes until a restart.

## For a human to check in the browser (after restarting the dev backend)

1. Login lockout: on `/login` type a wrong password 6 times quickly for any username. The 6th shows "Too many attempts. Try again in N seconds." (N up to 12). Wait that long and a correct login must work again. Try the same with a different (even non-existent) username after 5 attempts from the same browser: still blocked by the IP bucket, which is expected.
2. Register: create 6 accounts within an hour from one machine; the 6th shows the message with a long wait (up to about 12 minutes per further account).
3. Password change on `/profile`: 6 attempts with a wrong current password within 15 minutes; the 6th shows the message, and the session stays logged in.
4. Normal flow: a few logins, transfers and page navigation must never show the message.
5. To get past a limit while testing, restart the backend (counters are in memory) or start it with `--app.rate-limit.enabled=false`.
