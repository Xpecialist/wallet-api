# wallet-api frontend

A React (Vite, JavaScript) UI for the wallet API: login/register, dashboard with withdraw, deposit requests (money is added only after an admin approves), transfers,
filterable history, profile, wallet lookup and an admin section (visible to the ADMIN role only).

## Prerequisites

- Node 20.19+ or 22.12+ (required by Vite 8; Vitest 5 needs 22.12+ or 24+) and npm
- The backend running on `http://localhost:8080` (see the root `README.md`; set `ADMIN_PASSWORD` to get an admin user)

## Install and run

```
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. The dev server proxies `/api` to the backend, so no CORS setup is needed.

## Build

```
npm run build     # output in frontend/dist
npm run preview   # serve the production build locally
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_PROXY_TARGET` | `http://localhost:8080` | Backend the dev server proxies `/api` to (dev only) |
| `VITE_API_BASE_URL` | empty (same origin) | Prefix for API calls, for a build served from a different origin than the API. That backend would then need CORS enabled for the UI origin |

Put them in `frontend/.env.local` or the environment.

## Structure

```
src/
  api/client.js        fetch wrapper: base URL, Bearer token, Idempotency-Key, error parsing, endpoint functions
  auth/AuthContext.jsx token, current user (with role) and wallet; sign in/out; 401 handling
  components/          Layout (nav bar, route guards), Fields, DataTable, Pagination, Feedback (Alert, Loading),
                       AmountForm, StatusBadge, TransactionTable/Detail/Browser
  pages/               Auth, Dashboard, Transfer, Deposits, History, Profile, WalletLookup, Admin
  utils/               money/date formatting, direction helper, useFetch hook
```

## Notes

- The JWT is kept in memory and in `sessionStorage` (cleared when the tab closes). Any 401 on an authenticated call
  clears it and sends you to the login page.
- The role comes from `GET /api/users/me`; the login response only contains the token.
- Each withdrawal, transfer and admin deposit submit sends a new `Idempotency-Key`.
- Amounts are shown with 2 to 4 decimals plus the currency of your wallet.
- Deposits (`/deposits`, nav link and a dashboard card): a form (amount + note or bank reference) that creates a deposit request, and a paged list of your requests with a status filter and PENDING/APPROVED/REJECTED badges. Nothing is credited until an admin approves; each submit sends a new `Idempotency-Key` and the form ignores a second submit while one is in flight.
- Admin > Deposit requests (`/admin/deposit-requests`): status filter (default PENDING), Approve and Reject buttons (reject asks for a required reason). Buttons are disabled while a call runs, and the list reloads afterwards (also after an error such as "already decided"). The `/admin` routes stay behind the ADMIN guard.
- Tests: `npm test` (Vitest); `tests/deposits.test.jsx` covers the deposit request pages.

## Design system

Dark-first, minimal. Everything lives in `src/styles.css` (sections: tokens, base, layout, cards, forms, buttons, feedback, tables, badges, dashboard, responsive). No fonts or icon libraries are downloaded; the font is the system UI stack and the only graphic is the inline SVG logo mark (`BrandMark` in `components/Layout.jsx`).

- **Tokens** (CSS custom properties on `:root`): surfaces `--bg`, `--surface`, `--raised`, `--field-bg`; lines `--border`, `--border-strong`, `--field-border`; text `--text`, `--text-2`, `--text-muted`; one accent `--accent` (teal) with `--accent-hover/-active/-text/-soft`, `--on-accent`, `--focus-ring`; semantic `--money-in`, `--money-out`, `--success`, `--warning`, `--danger`, `--info` (+ `-bg` tints); type scale `--fs-xs..--fs-2xl` (12/14/16/20/28/36); spacing `--s-1..--s-12` (4px base); `--radius*`, `--shadow`, `--t` (150ms), `--control-h` (40px), `--content-w`.
- **Components**: `.navbar` (+ `.nav-admin`, `.nav-user`), `.subnav` (admin tabs), `.card`, `.balance-card` + `.quick-actions`, `.tx-list`, `.form`/`.filters`/`.field`, buttons (default primary, `.secondary`, `.danger`, `.ghost`, `.link`, `a.btn` for link-styled buttons), `.alert` (`-error`, `-success`, `-info`, `-warning`), `.loading`, `.empty`, `.table-wrap` + `table` (`DataTable` columns accept `numeric` and `cellClass`), `.tag` badges (`tag-pending`, `-approved`, `-completed`, `-rejected`, `-failed`, `-received`, `-deposit`, `-sent`, `-withdrawal`), `.pagination`.
- Amounts use `font-variant-numeric: tabular-nums`; money in is green with `+`, money out is neutral with `-` (the sign and the status text always accompany colour).
- **Adding a light theme later**: components only use the tokens, so add a block that overrides them, for example `:root[data-theme='light'] { color-scheme: light; --bg: #f6f7fb; --surface: #fff; --raised: #f0f2f7; --text: #141a26; ... }` (re-check contrast, including `--on-accent`, the `-bg` tints and `--field-border`), then set `data-theme` on `<html>`. A few hover colours (`#1f2638`, `#3d4860`, `#7b86a0`) and the SVG logo fill are still literal values and should be tokenised at that point.
- **Round 2 additions**: tokens `--content-w-wide` (1200px, admin pages: `main` gets `container-wide`) and `--form-width` (480px, `.narrow` form cards). `DataTable` column options `actions` (pinned right with a left shadow), `title(row)` (tooltip for truncated cells); table props `rowClass(row)` and `cards` (<= 720px: stacked label/value cards using each cell's `data-label`). Badges `tag-admin`, `tag-user`, `tag-enabled`, `tag-disabled`. `Form` takes `submitClass` (e.g. `danger`). `Loading` takes `page` (full-screen centred). `Alert` renders messages starting with "Too many attempts." (HTTP 429) with the amber warning style. Transaction filters are collapsible behind a "Filters" toggle (`aria-expanded`) on <= 720px only; the fields stay in the DOM. Phone nav is one horizontally scrollable row. `.grid-equal` gives equal-height cards.
