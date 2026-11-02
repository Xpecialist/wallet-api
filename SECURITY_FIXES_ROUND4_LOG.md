# Security fixes, round 4 (third group of SECOND_CODE_REVIEW.md: L-1, L-2, L-8)

Scope: only the three items below. Nothing else from the review was touched. No commit was made.

## L-1: IPv6 rotation bypass of the per-IP rate limits

**Confirmed.** Two throwaway tests on the unmodified code failed: `clientIp` returned a different key for `2001:db8:1:2::1` and `2001:db8:1:2::2` (same /64), and with `trust-forwarded-headers=true` the values `dead:beef:::::` and `1.2.3.4.5.6` were accepted as the client key (`looksLikeIp` only checks the character set).

**Design.** New package-private `security/ClientAddress.key(text, ipv6PrefixBits)`, a hand-written strict literal parser (no `InetAddress`, no DNS, no regex backtracking):
- IPv4: four decimal parts of 1-3 digits, 0-255, no leading zeros; key is the dotted form.
- IPv6: at most one `::`, groups of 1-4 ASCII hex digits, optional embedded IPv4 as the last 32 bits, 8 groups after expansion; `::` must stand for at least one group; optional zone id (`%` + up to 32 of `[A-Za-z0-9_.-]`, IPv6 only) is stripped; total length capped at 64.
- `::ffff:a.b.c.d` (and its hex form) maps to the IPv4 key.
- IPv6 key = the 8 groups masked to the first N bits, lower-case hex, so case, compression and zone never matter.
- `RateLimits.clientIp` now: (trusted mode) take the XFF entry, key it, if it is not a valid literal fall back to the peer; the peer is keyed the same way (an unparsable peer string is kept as is). `ipv6-prefix-bits` outside 1-128 fails startup with `IllegalArgumentException("app.rate-limit.ipv6-prefix-bits must be between 1 and 128")`.

**Files.** `security/ClientAddress.java` (new); `security/RateLimits.java` (before: returned the raw peer or a lower-cased `looksLikeIp` header entry; after: normalized keys, `looksLikeIp` and `MAX_IP_LENGTH` removed); `security/RateLimitProperties.java` (+`ipv6PrefixBits`, default 64); `application.properties` (+property and comment); `README.md` (properties list, new "IPv6 clients" paragraph with the shared-/64 trade-off).

**Tests added.** `ClientAddressTest` (10): spellings share a key (compressed/expanded/mixed case/zone/mapped), same /64 shares, different /64 does not, IPv4 unaffected, embedded IPv4, ~30 invalid literals rejected, configurable bits (48, 60, 128, 1), `clientIp` with peer and forwarded entries, invalid XFF falls back to the peer, out-of-range bits fail. `RateLimitIT.rotatingTheLow64BitsOfAnIpv6ClientDoesNotResetTheLoginIpBucket` (MockMvc, `remoteAddr` rotated, upper-case and zone-id spellings, another /64 unaffected). `RateLimitTrustedProxyIT` +2 (rotating forwarded IPv6 low bits; invalid forwarded value falls back to the proxy bucket).
One existing assertion in `RateLimitFilterTest` changed on purpose: it expected the key `2001:db8::1` for header `2001:DB8::1`; it now expects the /64 key `2001:db8:0:0:0:0:0:0` (that is the fix).

**Remaining risk.** Users sharing a /64 share limits (documented, configurable). An attacker with a /48 or larger gets 65k+ /64 buckets; lower the setting if that matters. Other IPv6 transition forms (6to4, NAT64, Teredo) are keyed as ordinary IPv6. `trusted-proxies` set too high is still operator error (documented earlier). Rate limits remain in memory per instance.

## L-2: default database credentials (old M7)

**Confirmed.** `application.properties` had `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}`. The new `DatabaseCredentialsGuardTest.mainPropertiesHaveNoDefaultCredentialsAndTheDevProfileHoldsThem` and the "fails fast" startup tests cannot pass on the old file (the app started with no variables set).

**Design.** Same pattern as the JWT secret: `config/MissingDatabaseCredentialsException`, `MissingDatabaseCredentialsFailureAnalyzer` (registered in `META-INF/spring.factories` next to the JWT analyzer), and `DatabaseCredentialsGuard`, a `BeanFactoryPostProcessor` that checks `spring.datasource.username/password` before any bean exists, so the DataSource and Flyway (which uses the same datasource credentials; no `spring.flyway.user/password/url` is set, and a test asserts that) are never created and no connection error or stack trace appears. Verified live on port 8081 against `walletdb_test` with no credentials: only the "APPLICATION FAILED TO START / Description / Action" report is printed, naming the missing variables (never values).
- Decision: NO default username either (a default of the `postgres` superuser nudges people to run with it); `DB_USERNAME` and `DB_PASSWORD` are both required outside the dev profile. This is stricter than the minimum asked; say so if you want `postgres` back as a username default.
- `spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/walletdb}`.
- `application-dev.properties` now holds `${DB_USERNAME:postgres}` / `${DB_PASSWORD:postgres}` with a warning comment next to the dev JWT secret. **This is the one deliberate exception to "no password in any file"**, mirroring the public dev JWT secret; the profile is only active when selected. `application-it.properties` keeps its own explicit test credentials for `walletdb_test` (with a comment); they are unchanged in value, so the integration suites need no new variable. `FlywayMigrationIT` already carried its own test defaults. No test depended on the main-file default (full `mvn verify` ran with `DB_PASSWORD` unset).
- README: variables table (`DB_URL`, required `DB_USERNAME`/`DB_PASSWORD`), run commands, and production notes (least-privilege user, `sslmode=verify-full` in `DB_URL`, rotate any credential or JWT secret that ever used the committed defaults because they remain in git history). `scripts/seed-dev-data.sh` does not reference the DB; unchanged.

**Files.** `config/DatabaseCredentialsGuard.java`, `config/MissingDatabaseCredentialsException.java`, `config/MissingDatabaseCredentialsFailureAnalyzer.java` (new); `META-INF/spring.factories`; `application.properties` (before: URL fixed, `postgres` defaults; after: `DB_URL` default URL, empty user/password); `application-dev.properties`; `src/test/resources/application-it.properties` (comment only); `README.md`; `JwtSecretStartupIT` (now passes DB credentials so it still reaches the JWT check).

**Tests added.** `DatabaseCredentialsGuardTest` (7, no database): fails without credentials / blank password / missing username, starts with both, analyzer text, analyzer registered, main file has no defaults and no flyway override, dev file has them. `DatabaseCredentialsStartupIT` (4, real app, `walletdb_test` only): no password -> fails with the clear exception; no username -> fails; with credentials -> starts; dev profile with no variables -> starts. (The two failing-start tests skip `TestDbGuard` because that guard itself connects with the credentials; they assert the test URL themselves and never reach a connection.)

**Remaining risk.** The password is still in git history (rotate; see README). The dev profile still publishes postgres/postgres (local only, binds 127.0.0.1). Credentials in environment variables are visible to same-user processes; use a secret manager where available. The guard checks "not blank", not strength.

## L-8: swallowed logout failure (frontend)

**Confirmed.** `signOut` ended in `.catch(() => {})`. Seven of the new tests fail against the unmodified `AuthContext.jsx` / `AuthPages.jsx` (checked by temporarily restoring them).

**Design.** `AuthContext` keeps one boolean, `logoutNotice` (+`dismissLogoutNotice`). `signOut` still clears the local session immediately. When the logout call rejects with anything except `ApiError` status 401 (network error = status 0, 5xx, 429, ...) it sets the flag, but only if no token is stored at that moment (a late failure after the user signed in again is ignored). `signIn` clears the flag; `LoginPage` clears it in an effect cleanup, so leaving the login page (to Register, or by logging in) removes it. `AuthPage` renders it with the existing `<Alert type="warning">` (class `alert-warning`, `role="status"`, so it is not an error alert) using the requested text. State is a flag only: no token, no sessionStorage, no retry, no loop (one logout call).

**Files.** `frontend/src/auth/AuthContext.jsx`, `frontend/src/pages/AuthPages.jsx`, `frontend/tests/auth.test.jsx` (new describe appended; no existing test edited or weakened).

**Tests added (10 cases, 4 of them via `it.each`).** Network error, 503, 500, 429 show the warning notice on /login and clear the session; 401 shows none and makes one call; 204 shows none; no retry; notice gone after navigating to Register and back; notice gone after a successful login and a later normal logout shows none; a failure arriving after a new login shows none; the notice text does not contain the token.

**Remaining risk.** The fetch has no client-side timeout, so a request that hangs forever produces no notice (a browser-level timeout surfaces as status 0 and does). If the page is reloaded before the logout failure arrives, the notice is lost (by design, no persistence). The real fix for a stale token is server-side expiry (1 hour).

## New properties

| Property | Default | Meaning |
|---|---|---|
| `app.rate-limit.ipv6-prefix-bits` | `64` | IPv6 prefix length (1-128) that identifies a client for per-IP limits; out of range fails startup |
| `DB_URL` (env) -> `spring.datasource.url` | `jdbc:postgresql://localhost:5432/walletdb` | JDBC URL |
| `DB_USERNAME` (env) | none (required; `postgres` only in `dev` profile) | DB user, also used by Flyway |
| `DB_PASSWORD` (env) | none (required; public dev default only in `dev` profile) | DB password, also used by Flyway |

## Results

| Command | Result |
|---|---|
| `mvn test` | 310 tests, 0 failures (was 293: +10 ClientAddressTest, +7 DatabaseCredentialsGuardTest) |
| `mvn verify` | BUILD SUCCESS; 258 integration tests, 0 failures (was 251: +1 RateLimitIT, +2 RateLimitTrustedProxyIT, +4 DatabaseCredentialsStartupIT); run with `DB_PASSWORD` unset in the environment |
| `cd frontend && npm test` | 279 passed (was 269, +10) |
| `cd frontend && npm run build` | success |

## Could not verify

- A real browser run of the logout notice (jsdom tests only); the visual look of the warning alert.
- Real IPv6 traffic end to end (tests set `remoteAddr` in MockMvc; the real-HTTP IT uses IPv4 loopback).
- A production start against a database with a least-privilege user and `sslmode=verify-full` (no such database here).
- The already running dev backend on 8080 was not restarted, so it still runs the classes it loaded earlier; the dev-profile start was verified by `DatabaseCredentialsStartupIT.devProfileStartsWithoutAnyCredentialVariables` and the no-credentials failure by a live boot on port 8081 (stopped afterwards).

## How to start the backend now

Dev (unchanged; uses the public dev JWT secret and postgres/postgres defaults, binds to 127.0.0.1):

```
$env:ADMIN_PASSWORD = "<your dev admin password, at least 12 characters>"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Production style (no profile; all three are required, otherwise a clear "failed to start" message):

```
$env:DB_URL = "jdbc:postgresql://db.internal:5432/walletdb?sslmode=verify-full"
$env:DB_USERNAME = "<least-privilege user>"
$env:DB_PASSWORD = "<database password>"
$env:JWT_SECRET = "<base64, at least 32 bytes decoded>"
$env:ADMIN_PASSWORD = "<strong password>"
java -jar target/wallet-api-0.0.1-SNAPSHOT.jar
```

Anyone who previously started WITHOUT the dev profile and relied on the `postgres` defaults must now set `DB_USERNAME` and `DB_PASSWORD`.
