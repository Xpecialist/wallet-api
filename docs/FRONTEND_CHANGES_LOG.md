# Frontend changes log

Fixes for the 3 open frontend bugs from TEST_FINDINGS.md. Branch `Testing`, not committed.

## 1. Profile discarded when the wallet call fails (AuthContext)
- Root cause: confirmed. `Promise.all([api.me(), api.myWallet()])` rejected as a whole, so neither `user` nor `wallet` was set.
- Changed: `src/auth/AuthContext.jsx`, profile/wallet load effect.
  - Before: `Promise.all(...).then(([u, w]) => { setUser(u); setWallet(w); }).catch(() => {})`
  - After: `Promise.allSettled(...)`; `setUser` only if the profile call fulfilled, `setWallet` only if the wallet call fulfilled. The `.catch` is gone (allSettled never rejects).
- 401 behavior unchanged: the API client's unauthorized handler (`clear`) still runs on any 401, clearing token and user.
- Null-wallet audit (no change needed): DashboardPage returns "Could not load your wallet."; TransferPage uses `wallet &&` / `wallet?.currency`; HistoryPage uses `wallet?.id`; ProfilePage guards `!user`; Layout guards `user &&`.
- Test now passing: `a failing wallet call (404/500) discards the successfully loaded profile (Promise.all in AuthContext)` (auth.test.jsx), `BUG:` prefix removed. The test name still mentions Promise.all and describes the old bug; kept as is.
- Remaining risk: with a failed wallet, `wallet` stays null until `refreshWallet()` is called; Dashboard/Transfer show no balance and there is no retry button. If `/users/me` fails but the wallet succeeds, ProfilePage shows its error and Layout shows no username/Admin link.

## 2. Whitespace-only receiver (TransferPage)
- Root cause: confirmed. `required` accepts "   "; trimming happened only when building the body.
- Changed: `src/pages/TransferPage.jsx`, `submit`.
  - Before: set busy, build body with `receiver.trim()`, send.
  - After: trim first; if empty, `setError('Receiver username is required.')` (or `'Receiver wallet ID is required.'` in wallet-ID mode) and return before setting busy or calling the API. Body uses the trimmed value in both modes.
- Test now passing: `a receiver that is only whitespace passes client validation and is sent as an empty username` (money.test.jsx), `BUG:` prefix removed (name now inaccurate but kept).
- Remaining risk: wallet-ID mode uses `type=number`, so whitespace cannot normally be entered there; the check is a safeguard only. The error is shown in the existing Alert below the form, not next to the field.

## 3. formatMoney(null) (format.js)
- Root cause: confirmed. `Number(null)` is 0.
- Changed: `src/utils/format.js`, `formatMoney`.
  - Before: `Number(value)` then NaN check.
  - After: returns '-' first for `null`, `undefined` and `''`; NaN check kept. Valid numbers unchanged.
- Test now passing: `null is formatted as 0.00 instead of "-"` (format.test.js), `BUG:` prefix removed.
- Remaining risk: `Number('  ')` is still 0 and formats as 0.00 (not covered by tests, not changed).

## Results

| Check | Result |
|---|---|
| `npm test` before | 182 passed, 3 failed (the 3 `BUG:` tests), per TEST_FINDINGS |
| `npm test` after | 7 files passed, 185 tests passed, 0 failed |
| `npm run build` | succeeded (index.js 186.58 kB, gzip 59.71 kB) |
| Tests weakened or deleted | none; only the `BUG:` prefix was removed from 3 test names |
