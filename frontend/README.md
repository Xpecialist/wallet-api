# wallet-api frontend

A React (Vite, JavaScript) UI for the wallet API: login/register, dashboard with withdraw (money is added only by an admin), transfers,
filterable history, profile, wallet lookup and an admin section (visible to the ADMIN role only).

## Prerequisites

- Node 18+ and npm
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
                       AmountForm, TransactionTable/Detail/Browser
  pages/               Auth, Dashboard, Transfer, History, Profile, WalletLookup, Admin
  utils/               money/date formatting, direction helper, useFetch hook
```

## Notes

- The JWT is kept in memory and in `sessionStorage` (cleared when the tab closes). Any 401 on an authenticated call
  clears it and sends you to the login page.
- The role comes from `GET /api/users/me`; the login response only contains the token.
- Each withdrawal, transfer and admin deposit submit sends a new `Idempotency-Key`.
- Amounts are shown with 2 to 4 decimals plus the currency of your wallet.
