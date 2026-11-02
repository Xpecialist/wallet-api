# Frontend dependency upgrade log (finding F11)

Date: 2026-10-23. Branch `Testing`. Node v24.20.0, npm 11.19.0. Not committed.

## Versions
| Package | Before | After | Why |
|---|---|---|---|
| react-router-dom | ^6.26.0 (6.30.6) | ^7.18.4 | latest 7.x; fixes open redirect advisory; node >=20 |
| vite | ^5.4.0 (5.4.21) | ^8.3.5 | latest; fixes vite/esbuild advisories (6.4.4+ needed); node ^20.19 or >=22.12 |
| @vitejs/plugin-react | ^4.3.1 | ^6.1.2 | v6 is the line whose peer is vite ^8 |
| vitest | ^2.1.9 | ^5.0.5 | fixes vitest/vite-node/mocker and tinypool 2 (critical); node ^22.12 or ^24 or >=26; peer vite ^6.4/7/8 |
| jsdom, @testing-library/*, react, react-dom | unchanged | unchanged | worked as is (jsdom 25.0.1 passes) |

Steps (each followed by `npm test` and `npm run build`, all green, 218/218):
1. react-router-dom 7.18.4 alone.
2. vite 6.4.5 + vitest 3.2.7 (intermediate; audit then showed 3 vulns: vitest/tinypool still open).
3. vite 8.3.5 + @vitejs/plugin-react 6.1.2 + vitest 5.0.5 (final). No `npm audit fix --force` used.

## Code/config changes
- `frontend/package.json` and `package-lock.json`: versions above only.
- `frontend/README.md`: prerequisite "Node 18+" -> "Node 20.19+ or 22.12+ (required by Vite 8; Vitest 5 needs 22.12+ or 24+)".
- No change to `src/`, `tests/`, `vite.config.js` (still reads VITE_API_PROXY_TARGET, port 5173) or `vitest.config.js`. Imports from `react-router-dom` still work in v7 (re-export); no future flags were in use, BrowserRouter/Routes/Route/Navigate/Outlet/useNavigate/Link/NavLink unchanged. Still a client-side SPA, no framework mode/SSR.

## Behavior differences found
- None in tests. The pre-existing React `act(...)` warning for TransactionBrowser seen before the upgrade is gone after it (0 occurrences).
- Production bundle: 207.8 kB JS before-step vs 207.0 kB final (vite 8 / rolldown build); build ~0.2 s.
- Dev server smoke test on port 5175 (stopped afterwards): `/` 200, `/login` 200 (SPA fallback), `/api/x` 502 (proxy active, backend not running, as expected).

## Test results
- Baseline: 8 files, 218 tests passed. After each step and at the end: 8 files, 218 tests passed. No test was changed.
- `npm run build`: success at each step.

## Final audit
- `npm audit`: found 0 vulnerabilities
- `npm audit --omit=dev`: found 0 vulnerabilities
(before: 8 total = 5 moderate, 1 high, 2 critical; prod 2 moderate)

## Remaining risks / not verified
- Node: the fully patched line needs Node ^20.19 or >=22.12 for Vite 8, and Vitest 5 needs ^22.12 or ^24 or >=26. Installed Node 24.20 is fine; CI/dev machines on Node 18/20.x older than 20.19 will fail. Vitest 5 on Node 20 is not supported.
- Real browser behavior of the v7 router was not verified (jsdom tests use MemoryRouter). A human should click through: login (redirect to dashboard after success), visiting a protected route logged out (redirect to login), admin-only route as a normal user (redirect), logout (back to login, token cleared), history filters/search (URL/search params, browser back/forward), deep link/refresh on a nested route, and the admin section as ADMIN.
- Production `vite build` output (rolldown) was built but not exercised in a browser or via `vite preview`.
- vite 8 and plugin-react 6 are new majors with a different bundler (Rolldown/Oxc); watch for differences in minification/CSS output.
