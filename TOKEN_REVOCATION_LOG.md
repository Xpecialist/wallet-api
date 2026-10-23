# Token revocation (finding L4), 2026-10-23

Problem (TEST_FINDINGS.md L4): a JWT (sub/iat/exp, 1 hour) stayed valid after a password change, a logout (there was none) or a demotion. Only a disabled or deleted user was rejected, because `JwtAuthFilter` loads the user on every request.

## Design: per-user token version

- New column `users.token_version BIGINT NOT NULL DEFAULT 0`.
- Every token carries the user's version at issue time in the claim `tv` (login, register, and the fresh token after a password change).
- `JwtAuthFilter` rejects (unauthenticated, so 401 on protected endpoints, same as an invalid token) any token whose `tv` is missing or not equal to the stored version. The stored version is read in the SAME user load the filter already does, so there is no extra query and no extra DB hit per request.
- The version is bumped with one atomic native `UPDATE users SET token_version = token_version + 1 WHERE id = ?` (`UserRepository.bumpTokenVersion`). No read-modify-write, so parallel bumps cannot be lost.
- The entity maps the column with `updatable = false`. This matters: Hibernate updates all columns of a dirty entity, so a stale entity (for example one loaded before a bump and saved after it) would otherwise write the old version back and silently undo a revocation. Only the atomic UPDATE changes the value.
- Why this and not `passwordChangedAt`/`iat`: iat has second resolution and depends on clocks; a counter is exact, also covers logout and disable, and costs nothing.

Bumped on:

| Event | Where |
|---|---|
| `POST /api/auth/logout` (new, authenticated, 204) | `AuthService.logout` |
| successful password change | `UserService.changePassword` |
| admin DISABLES a user (enabling does not bump) | `UserService.setEnabled` |
| admin role change | does not exist in the code (no endpoint, `setRole` only used when creating a user). Nothing to bump; a future role change must call `bumpTokenVersion`. |

Tokens issued before this change have no `tv` claim and are REJECTED (401). After deploying, every user has to log in again once (the dev browsers with a stored session will be sent to the login page).

## Chosen behavior

- Logout = "log out of all devices": there is no per-device session in a stateless JWT, so one version per user. The token used for the call is revoked too.
- Password change: `POST /api/users/me/password` now answers **200 with a new token** (`{"token": ..., "tokenType": "Bearer"}`) instead of 204. Reason: the current session survives (better UX), all other devices and the old token die. The bump and the read of the new version happen in the same transaction: the UPDATE holds the row lock until commit, so the version read right after it is exactly the one this call produced, even if a logout runs in parallel (a concurrent logout waits for the lock and then invalidates the new token, as it should). The frontend stores the new token with `setToken`.
- Re-enabling a disabled user does not bump; because disabling already bumped, the old tokens stay dead and the user logs in again.
- `POST /api/auth/logout` is authenticated: a missing, invalid, expired or already revoked token gets 401. `SecurityConfig` has `/api/auth/logout` -> `authenticated()` before the `/api/auth/**` permitAll rule.

## Migration

`src/main/resources/db/migration/V2__user_token_version.sql`:

```sql
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
```

V1 is untouched. Existing rows get 0. A database baselined at V1 (old `schema.sql` databases) and a database created by V1 only both migrate cleanly (tests below). `SchemaShapeGuard`: `verify(...)` keeps checking exactly the V1 shape (it runs BEFORE a baseline, where the column cannot exist yet); new `verifyCurrent(...)` = V1 shape + `users.token_version` (bigint, not null), and runs after every migration. The dev `walletdb` gets V2 at its next backend start (NOT done by me: I did not touch `walletdb`).

## Code changes (file: before -> after)

- `entity/User.java`: no token field -> `@Column(name="token_version", nullable=false, updatable=false) long tokenVersion` + `getTokenVersion()` (no setter).
- `repository/UserRepository.java`: + `int bumpTokenVersion(Long id)` (native atomic UPDATE, `@Modifying(flushAutomatically, clearAutomatically)`), + `long findTokenVersion(Long id)` (JPQL scalar, always hits the DB).
- `security/AuthUser.java` (new): Spring `User` subclass carrying `tokenVersion`.
- `security/SecurityConfig.java`: `userDetailsService` built a plain Spring `User` -> builds `AuthUser` with the stored version (authorities `ROLE_<role>`, enabled flag as before); `/api/auth/logout` requires authentication.
- `security/JwtService.java`: `generateToken(username)` -> `generateToken(username, tokenVersion)` adding claim `tv`; new `parse(token)` returning `TokenClaims(username, tokenVersion|null)`; `extractUsername` kept (delegates). Signature, expiry and algorithm checks unchanged. A non-numeric `tv` parses as null (rejected).
- `security/JwtAuthFilter.java`: `extractUsername` + enabled check -> `parse` + enabled check + `tv != null && tv == stored.getTokenVersion()`. A principal that is not an `AuthUser` is rejected (fail closed).
- `service/AuthService.java`: `register` issues the token with the new user's version (0); `login` takes the version from the `AuthUser` principal that was loaded for the password check (so a change between the check and the issue can only make the token stale, never valid for an old password); new `logout(username)`.
- `controller/AuthController.java`: new `POST /api/auth/logout` -> 204.
- `service/UserService.java`: constructor takes `JwtService`; `changePassword` returns `AuthResponse` (bump + fresh token); `setEnabled` bumps when disabling (response is built before the bump clears the persistence context).
- `controller/UserController.java`: `POST /api/users/me/password` returns `AuthResponse` (200) instead of 204.
- `config/SchemaShapeGuard.java`: `verifyCurrent` (see Migration); the post-migration check uses it.
- Frontend: `api/client.js` (+ `api.logout`; a 401 only signs out if the token that was SENT is still the current one, so a late 401 of the logout call cannot sign out a session that logged in meanwhile), `auth/AuthContext.jsx` (`signOut` = best-effort `api.logout()` then always `clear()`; the 401 of an already revoked token only clears again, no loop, and the call is not retried), `pages/ProfilePage.jsx` (stores the token returned by the password change). `Layout.jsx` needed no change (it already calls `signOut`). A 401 from a revoked token on any other request goes through the existing handler to the login page.
- README.md: section "Token revocation", API table, curl examples, data model, schema section (next migration is V3), design notes.

Not weakened: disabled-login timing (provider unchanged), 72-byte rule, text validation, 404 for foreign ids, sort whitelist, limits, idempotency, deposit-request approval, secret fail-fast. Admin deactivation/re-enabling still works; the only visible change is that a re-enabled user needs a fresh login.

## Tests

Existing tests changed only where the contract changed: `generateToken` has a second argument; the filter tests use `AuthUser`; password change answers 200 + token (`ApiWebLayerTest`, `AuthAndAccessIT`, `SecurityFixesIT`; `AuthAndAccessIT` keeps using the fresh token afterwards); `AuthAndAccessIT.disabledUserCannotLoginAndExistingTokenStopsWorking` previously asserted that the OLD token works again after re-enabling, now asserts it stays dead and a fresh login works (this is the requested behavior); `FlywayMigrationIT` expects 2 migrations/history rows and `verifyCurrent`.

Added:

- `JwtServiceTest` (+4): `tv` claim round trip, token without `tv` parses null, non-numeric `tv` null, `parse` still rejects expired/foreign/garbage.
- `JwtAuthFilterTest` (+5): matching version accepted, lower/higher rejected, missing claim rejected, principal without version rejected, disabled user with matching version rejected.
- `AuthServiceTest` (+4), `UserServiceTest` (+3), `ApiWebLayerTest` (+2): version in login/register token, logout bumps only the caller, bump order on password change, no bump on failed change, bump only on disable, logout 401/204, wrong version 401 also on logout.
- `TokenRevocationIT` (18, on `walletdb_test`): tokens carry the version; no-`tv` token rejected everywhere and cannot bump; wrong version (lower, higher, negative) rejected; old token rejected after password change while the returned one works and other sessions die; failed change revokes nothing; other users untouched; logout kills all sessions of the user (3 tokens) but not another user's; logout needs auth; login after logout works; 24 parallel `authService.logout` give exactly +24; 12 parallel HTTP logouts only 204/401 and the token is dead; parallel password change + logout never leaves an old token alive; admin disable bumps (and enabling does not), login of a disabled user still 401, old token dead after re-enable, fresh login works; disabled user with matching version still rejected; self-disable still 400 and does not bump; revoked admin token loses admin access.
- `FlywayMigrationIT` (+2): a V1-only database (built by running V1 through Flyway, with users/wallets/transactions) migrates to V2 keeping all data, `token_version` 0, `bigint/NO/0`, new rows default 0, `verifyCurrent` passes and the app starts (Hibernate validate); the "current shape" check names a missing `token_version` while the V1 check still passes. The existing baseline test (V1 shape without history) now also checks V2 runs on it and rows get 0.
- Frontend (+12): client (logout request, changePassword returns the token, late 401 for an old token is ignored, 401 for the current token clears once), auth (logout calls the endpoint with the token and clears, still clears when offline or 500, a 401 from logout does not loop or alert, a late logout 401 does not kill a newer session, revoked token goes to login once), profile (fresh token stored and used afterwards, failed change keeps the token).

## Results

| Command | Result |
|---|---|
| `mvn test` (via `mvn verify`) | 239 unit tests, 0 failures (before: 221) |
| `mvn verify` integration | 174 integration tests, 0 failures (before: 154); BUILD SUCCESS |
| `cd frontend && npm test` | 230 passed (before: 218) |
| `npm run build` | ok |

(I ran `mvn -o verify` without `clean`, so the running dev backend on 8080 was not stopped. I did not start the app on 8081 either: the integration tests start the full application against `walletdb_test`, including a Flyway V1->V2 start.)

## Remaining risks and limits

- The version check adds no query (same user load as before). Clock and `iat` are unaffected.
- Revocation is per user, not per device: logout, and a password change, end all sessions.
- A user whose password change succeeds but whose response is lost (network) is signed out on the next request (old token revoked) and has to log in with the new password.
- In the frontend, requests that are in flight with the old token while the password change completes may get 401 and sign the user out; the profile page sends them sequentially, so this window is tiny.
- Any new code path that changes the password hash, the role or `enabled` must also bump (no DB trigger enforces it). There is no role-change endpoint today.
- Tokens issued before the deploy are rejected (one forced re-login for everyone).
- `token_version` is a BIGINT counter; overflow is not a practical concern.
- Not verified: the migration on the real dev `walletdb` (not touched), behavior of a running multi-instance setup (single instance assumed; the database is the shared state, so it should work), no browser click-through.
- While `mvn verify` rewrote `target/classes`, the dev backend on 8080 kept running its already loaded old code; restart it to get the new code (it will apply V2 to `walletdb` at that start, and old browser sessions will be sent to the login page).

## What a human should click through

1. Log in; the dashboard loads.
2. Log in as the same user in a second browser/profile (session B). In session A press Log out: A goes to the login page; in B any navigation or reload now ends at the login page (revoked token, no error loop).
3. Log in twice again (A and B), change the password on A (Profile): A shows "Password changed." and keeps working (no redirect); B is sent to login on its next request; B can log in with the new password only.
4. Wrong current password on the profile form: error shown, still signed in.
5. As admin disable a logged-in user: that user's next request goes to login and login is refused; re-enable: the old session stays out, a new login works.
6. Edit the stored token in the browser (sessionStorage `wallet.token`) to garbage or leave an old token: reload goes to login.
7. Stop the backend and press Log out: the app still signs you out locally.
