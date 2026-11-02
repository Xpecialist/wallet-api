# Case study: building a production-ready wallet app with a team of AI agents

**What this is.** A digital wallet (Spring Boot 4 + PostgreSQL + React) built by AI agents (builders, testers, reviewers) that I directed and checked. The point of the project is not the wallet itself. It is to show how to *manage* AI agents so the result is something you can trust, because AI-written code forgets things, drifts from the docs, and sometimes makes decisions the human never asked for.

**Try it yourself:** [DEMO.md](DEMO.md) (about 10 minutes). **Full detail:** every problem below links to the log that records it.

## 1. How the work was organised

| Role | What it did | Rule it worked under |
|---|---|---|
| **Me (human lead)** | Set goals, made every product and trade-off decision, approved each step | Nothing is "done" until I have seen it verified |
| **Builder agents** | Wrote and changed code to a written spec | Match existing style, no unrelated refactors, write a log of every change, do not commit |
| **Tester agents** | Wrote unit and integration tests; ran attack-style security tests against their own copy of the app | Never touch real data or running servers; report real numbers, never claim a pass they did not see |
| **Reviewer agents** | Read the whole codebase, read-only | Verify each finding in code before reporting it; no inventing issues |

Every round followed the same loop: **brief the agent, let it work, re-run its checks myself, try the result live, then commit.** Agent reports were treated as claims to verify, not as facts.

## 2. What was built (short)

- **Backend:** registration/login (JWT), one wallet per user, transfers, withdrawals, transaction history with filters and paging, profile, admin tools, and **deposit requests** (a user asks for money, an admin approves it, the wallet is credited exactly once).
- **Frontend:** React app with user and admin screens, rebuilt as a dark, minimal UI.
- **Quality bar:** 310 unit + 258 integration (real PostgreSQL, including concurrency) + 279 frontend tests; two full code reviews; two attack-style security passes; dependency scans with 0 known advisories.

## 3. Problems we hit, how we found them, how we fixed them

The first version already "worked" and had passing tests. The problems below were found *after* that, by deliberately looking for them.

| # | Problem | How it was found | Fix | Detail |
|---|---|---|---|---|
| 1 | **Concurrent requests on one wallet returned HTTP 500** (stale data under a database lock) | Integration tests on a real database. The mock-based unit tests could not see it | Reload the wallet after taking the lock; map lock conflicts to a clean 409 | `CHANGES_LOG.md` |
| 2 | **Docs said default page size 20, code returned 10** | A unit test compared behaviour with the documented contract | Set the size explicitly on every list endpoint | `CHANGES_LOG.md` |
| 3 | **Free money:** any user could deposit to themselves, new accounts started with 100 | Code review. I decided this was not intended | Removed the endpoint; money enters only via admin-approved deposit requests | `SECURITY_FIXES_LOG.md`, `DEPOSIT_REQUESTS_LOG.md` |
| 4 | **Secrets committed in config** (a default JWT secret and database password) | Code review | App refuses to start without them; throwaway values live only in a `dev` profile | `SECURITY_FIXES_LOG.md`, `SECURITY_FIXES_ROUND4_LOG.md` |
| 5 | **Stolen login tokens survived a password change or logout** | Code review | Per-user token version; logout, password change and admin disable invalidate old tokens | `TOKEN_REVOCATION_LOG.md` |
| 6 | **Duplicate-request protection (idempotency keys) was weak:** keys were global, a changed payload was accepted, simultaneous duplicates got a vague 409 | Review, then attack pass, then parallel tests | Keys scoped per user, payload compared, check moved behind the wallet lock; UI reuses the key after a lost connection | `SECURITY_FIXES_LOG.md`, `IDEMPOTENCY_REPLAY_LOG.md` |
| 7 | **Odd input caused 500 errors** (null characters, huge page numbers, extreme dates, malformed bodies) and login timing revealed disabled accounts | First attack pass | Validation and bounds; clean 4xx errors; equal timing for all login failures | `SECURITY_TEST_REPORT.md`, `SECURITY_FIXES_ROUND2_LOG.md` |
| 8 | **Rate limiting could be bypassed by URL-encoding the path** (`/%61pi/...`): 300 wrong logins in 2.4 s were never limited. **All tests were green** | Second code review spotted it by reading; second attack pass reproduced it against a real server | Match the decoded path; new tests that talk to a real server | `SECOND_CODE_REVIEW.md`, `SECURITY_TEST_REPORT_2.md`, `SECURITY_FIXES_ROUND3_LOG.md` |
| 9 | **More rate-limit and account gaps:** padded login bodies skipped the per-user limit, an attacker could lock a victim out, a password change racing an admin "disable" could silently re-enable the user | Same second review and attack pass | Fail-closed body handling, a lockout-resistant limit design (trade-offs written down), targeted database updates, extra DB constraints | `SECURITY_FIXES_ROUND3_LOG.md`, `RATE_LIMITING_LOG.md` |
| 10 | **Outdated framework** (Spring Boot 3.3.5, out of support) and a schema script that re-ran on every start | Review plus an open-source vulnerability scan | Upgraded to Spring Boot 4.1.2; replaced the script with versioned Flyway migrations; scans now show 0 advisories | `SPRING_BOOT_UPGRADE_LOG.md`, `FLYWAY_LOG.md`, `VULNERABILITY_SCAN_REPORT.md` |
| 11 | **UI looked fine to the tests but not to a person:** the admin Approve/Reject buttons were cut off, and one page went blank from a stale dev-server cache | Looking at real screenshots in a browser (242 UI tests were green) | Reworked layout for desktop and phone; restarted the dev server to confirm | `UI_REDESIGN_LOG.md` |

## 4. Decisions the agents made without being asked (and how they surfaced)

Agents were required to report anything they did beyond the brief. These were caught in those reports or in review, then accepted or changed by me:

- A change to the data model made API amounts appear as `10.0000` instead of `10` (needed for byte-identical replays).
- Database credentials got **no default username** either, stricter than requested.
- Usernames became **ASCII-only** to block look-alike characters; I accepted the trade-off (no non-English names).
- A blank idempotency key now returns 400 instead of silently meaning "no key".
- When rate limiting was added, **nobody remembered the demo seed script** would break; the second review caught it.
- The agents' own README claimed the login limit was "never a lockout". The second pass proved that wrong, and the docs were corrected.

## 5. What the pattern shows

1. **Code and tests written by the same agent share blind spots.** The rate-limit bypass passed every test the agent wrote. An independent attacker agent found it in minutes.
2. **Different kinds of checks find different bugs:** mocks missed the concurrency bug, tests missed the UI problem, reading the code found race conditions that no test exercised.
3. **Verify, then trust.** I re-ran the test suites after every agent report and tried key fixes against the running app.
4. **The human owns the trade-offs:** what counts as a bug, what to parallelise, what to park, what is "good enough" (the UI did not need to be perfect).
5. **Write everything down.** Each change has a log, and `TEST_FINDINGS.md` indexes them all.

## 6. Honest limits

Not verified: a real reverse proxy and HTTPS deployment, real IPv6 traffic, more than one server instance (rate-limit counters are per process), and real-device browsers. Small known items remain (listed at the end of `TEST_FINDINGS.md`): admin can approve their own deposit request, requests never expire, a few input-handling nits, and an N+1 query on one admin list.

## 7. Where to look

| If you want... | Open |
|---|---|
| The master list of findings and decisions | `TEST_FINDINGS.md` |
| The two independent reviews and attack passes | `SECOND_CODE_REVIEW.md`, `SECURITY_TEST_REPORT.md`, `SECURITY_TEST_REPORT_2.md` |
| The fixes, in order | `SECURITY_FIXES_LOG.md`, `SECURITY_FIXES_ROUND2_LOG.md`, `SECURITY_FIXES_ROUND3_LOG.md`, `SECURITY_FIXES_ROUND4_LOG.md` |
| Feature logs | `DEPOSIT_REQUESTS_LOG.md`, `TOKEN_REVOCATION_LOG.md`, `RATE_LIMITING_LOG.md`, `IDEMPOTENCY_REPLAY_LOG.md`, `FLYWAY_LOG.md`, `SEED_DATA_LOG.md` |
| Upgrades and scans | `SPRING_BOOT_UPGRADE_LOG.md`, `FRONTEND_UPGRADE_LOG.md`, `VULNERABILITY_SCAN_REPORT.md` |
| The UI work | `UI_REDESIGN_LOG.md`, `FRONTEND_CHANGES_LOG.md` |
