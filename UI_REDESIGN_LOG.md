# UI redesign log (frontend only)

Branch `Testing`. Backend, databases and API behaviour untouched. No new dependencies. Not committed.

## Design decisions

Dark-first, minimal; one calm teal accent; everything driven by CSS custom properties on `:root` (`color-scheme: dark`), so a light theme can be added by overriding tokens (see frontend/README.md "Design system").

### Palette and contrast (WCAG 2.x ratios computed with a script)

| Token | Hex | Use | Contrast |
|---|---|---|---|
| --bg | #0b0e14 | page | - |
| --surface | #121722 | cards, top bar | - |
| --raised | #171d2b | table header, secondary buttons | - |
| --field-bg | #0f131c | inputs | - |
| --border | #232a3b | hairlines | decorative |
| --field-border | #5f6a82 | input outline | 3.56 on bg, 3.10 on raised (>= 3 for UI components) |
| --text | #e6e9f0 | body | 15.9 bg / 14.8 surface / 13.9 raised |
| --text-2 | #aab2c3 | secondary | 9.1 / 8.4 / 7.9 |
| --text-muted | #8b94a8 | muted, dates | 6.35 / 5.89 / 5.53 |
| --placeholder | #7b8499 | placeholders | 4.96 on field-bg |
| --accent | #2dd4bf | primary button bg | button text #04201c on it 9.18 |
| --accent-text | #5eead4 | links, active nav | 13.1 / 12.1 / 11.4 |
| --money-in / --success | #4ade80 | received, success | 11.1 / 10.3 / 9.7 |
| --money-out | #c3cad8 | sent, withdrawal (neutral) | > 9 (not computed exactly) |
| --warning | #fbbf24 | PENDING | 11.6 / 10.7; on tinted badge ~9.3 |
| --danger | #f87171 | REJECTED / FAILED / errors | 7.0 / 6.5 / 6.1; on tinted badge ~5.8 |
| --danger-solid | #b42318 | danger button, white text | 6.57 |
| --info | #7dd3fc | info alert accent | 11.6 |

Badge tints are 10% alpha overlays of the semantic colour; the badge ratios are approximations for the composited colour.

### Type, spacing, shape
- System UI font stack, no downloads. Scale 12/14/16/20/28/36 px (`--fs-xs..--fs-2xl`; h1 24 px and balance 30 px under 720px). Line-height 1.55, headings 700. `tabular-nums` on tables, number/date inputs, balance and transaction amounts; numeric columns right-aligned. A mono font token exists (`.mono`) but is not applied anywhere yet.
- Spacing 4px base (`--s-1..--s-12`). Radius 6/10/14. Shadow minimal (1px). Transitions 150ms; `prefers-reduced-motion` removes them and the spinner animation.
- Controls are 40px high (32px inside table cells on desktop, 40px on phones). Content column max 1040px.

## Component inventory
Top bar (sticky, logo mark + "Wallet", pill links with active state, Admin link separated, username + ghost Log out), admin sub-nav as segmented tabs, cards, balance card with Transfer / Add funds quick actions, "Latest transactions" list (direction tag, note truncated with ellipsis, muted date, signed coloured amount), forms with 40px inputs/selects/textarea and focus ring, buttons (primary, secondary, danger, ghost, link, `a.btn`; hover/active/disabled/busy), alerts (left accent bar; error/success/info/warning), loading (CSS spinner + text), empty state, tables (sticky header inside `.table-wrap`, hover, faint zebra, clickable rows, numeric alignment), badges, pagination as buttons, details lists (stack on phones), auth card with logo above.

## Files changed (all under frontend/)
- src/styles.css (rewritten)
- src/components/Layout.jsx (BrandMark SVG, nav aria-label, `nav-admin` class, username span class, Log out is `ghost`)
- src/components/Feedback.jsx (Loading uses `loading` class; same text)
- src/components/Fields.jsx (`aria-busy` on submit button while busy; same label text)
- src/components/DataTable.jsx (optional column props `numeric`, `cellClass`; empty state class)
- src/components/TransactionTable.jsx (amount colour span, Status column rendered with StatusBadge, numeric/note/date cell classes; text content unchanged)
- src/components/StatusBadge.jsx (comment only)
- src/pages/DashboardPage.jsx (balance card + quick actions; `RecentTransactions` list replaces the table on the dashboard; Withdraw form, "Add funds" card with "Request a deposit" link, "Wallet ID n" and API calls kept)
- src/pages/AuthPages.jsx (logo + name above the card)
- src/pages/DepositsPage.jsx, src/pages/AdminPages.jsx (column alignment/classes, `aria-label` on admin sub-nav)
- README.md (Design system section), UI_REDESIGN_LOG.md at repo root (this file)

## Tests adjusted
None. No test was changed, weakened or removed.

## Results
- `npm test`: 9 files, 240 of 240 tests passed.
- `npm run build`: OK; CSS 15.00 kB (4.01 kB gzip), JS 209.61 kB (66.89 kB gzip).
- Dev server on port 5175 only: `/` 200, `/login` 200, `/src/styles.css` 200; server stopped afterwards.
- I could not look at the result; no screenshots were taken.

## Known weaknesses / things to watch
- Not visually verified. Sticky `th` and `backdrop-filter` need modern browsers (fallback is a semi-opaque bar / non-sticky header).
- Nav on phones wraps into rows (brand + user first, links below) rather than a collapsed menu; with 7 links it takes 2-3 rows at 360px.
- `:user-invalid` (red border) is only in current browsers; others skip it.
- Dashboard has both the "Add funds" quick-action link and the "Add funds" card with the "Request a deposit" link (kept because a test clicks that link); slightly redundant.
- Dashboard recent list no longer shows From/To/Status columns (they remain in History).
- Status column in transaction tables is now a badge (COMPLETED green, PENDING amber, FAILED red).
- A few hover colours and the logo fill are literal hex values, not tokens (noted in the README for the light theme).
- Rate-limit (429) and other errors still show as the existing red `.alert` text; no special styling.

## Visual review checklist
Check each at desktop (about 1280px), tablet and phone (360-375px: no page-level horizontal scroll, tables scroll only inside their wrapper, tap targets comfortable):
- [ ] Login (centered card, logo above), error alert, busy button ("Please wait...")
- [ ] Register (three fields, long labels wrap)
- [ ] Dashboard: balance card with quick actions, Add funds card, Withdraw form (success + error alerts), Latest transactions with received/sent/deposit/withdrawal rows, long-note truncation, empty state, loading state, "Could not load your wallet." error
- [ ] Transfer (select + inputs, success/error)
- [ ] Deposits (form, My requests table, PENDING/APPROVED/REJECTED badges, empty text, pagination)
- [ ] History with filters (filter rows wrap, Apply/Reset, sort controls, numeric alignment, row hover, detail panel, pagination, empty state, "Page 0 of 0")
- [ ] Profile (details list, two forms side by side / stacked)
- [ ] Wallet lookup (result details, error)
- [ ] Admin users (enable/disable buttons, disabled self row), Admin transactions, Admin deposit requests (Approve/Reject, reject box, success/error alerts, status filter), Admin deposit (narrow form)
- [ ] Admin sub-nav tabs and active state; Admin link separation in the top bar; non-admin has no Admin link
- [ ] Rate-limit and generic error alerts, "Loading your account..." state
- [ ] Keyboard focus rings on links, buttons, inputs; reduced-motion setting

## Round 2 fixes (visual review at 1280px and 375px)

Frontend only, no backend/API change, no new dependencies, nothing committed. Not seen in a browser: only tests, build and a dev-server smoke test (port 5175: `/` 200, `/login` 200, `/src/styles.css` 200, stopped afterwards). `npm test`: 10 files, 242 of 242 pass (240 existing, unchanged, plus 2 new in `tests/alert.test.jsx`). `npm run build` OK (CSS 18.5 kB).

1. Admin tables reachable (HIGH)
   - `Layout.jsx`: `main` gets `container-wide` on `/admin*` (`--content-w-wide` 1200px, was 1040px). `DataTable.jsx`: new column props `actions` (class `col-actions`) and `title(row)`, table props `cards`, `rowClass`; every `td` has `data-label`. `styles.css`: `.col-actions` is `position: sticky; right: 0` with solid `--surface` background and a left hairline plus soft shadow; note columns `max-width` 24ch -> 20ch with ellipsis and `title` tooltip (Deposit requests note and decision note). At <= 720px `cards` tables (Users, Deposit requests) become stacked cards: header row visually hidden, each cell is "LABEL .... value", Approve/Reject (or Enable/Disable) are full-width buttons at the bottom, empty action cells (non-pending rows) hidden.
   - Before: Approve cut off / off-screen. After: pinned on desktop, card layout on phone. Labels unchanged.
   - Tests: none changed. Remaining weakness: All transactions has no action column; on phones it still scrolls sideways inside its wrapper (rows open the detail panel on click). In card mode the table loses native table semantics in some browsers (display: block), buttons keep names. Pinned cell is solid so zebra/hover tint is not shown on it.
2. Loading: `Loading` has `page` prop (`loading-page`: full-height flex centred) used for "Loading your account..."; `.card .loading` is now centred with more padding. Re-check: spinner centred on the page.
3. Filters on phones: `TransactionBrowser.jsx` has a "Filters" button (`aria-expanded`, `aria-controls`, caret) above a `.filter-panel` div wrapping the filter form and the sort row. At <= 720px the panel is hidden by CSS unless open (collapsed by default); on desktop the button is `display: none` and the panel always shows. Fields stay mounted, so tests are unaffected. The 400px rule that forced one column was removed: fields stay 2 per row. Weakness: sort/page-size selects are also inside the collapsed panel; filter state is kept when collapsed.
4. Reject flow: `Form` got `submitClass`; "Confirm reject" uses `danger`. The reason form now sits directly above the table (red left bar, red tint) and is scrolled into view (`scrollIntoView({block:'nearest'})`, guarded for jsdom); the targeted row gets `row-targeted` (red tint, red left inset bar; on phones a red-bordered card). Row "Reject" is secondary with a red outline/tint on hover (`reject-btn`). Labels/behaviour unchanged.
5. Users: Role and Status are `tag` badges (`tag-admin` teal, `tag-user` neutral, `tag-enabled` green, `tag-disabled` red), same text.
6. Dashboard: separate "Add funds" card removed; a muted line in the balance card ("Money is added only after an admin approves your request." + "Request a deposit" link) replaces it; the "Add funds" quick action stays. The existing test that clicks the link passes unchanged.
7. Profile: the two cards use `grid-2 grid-equal` (equal height, submit buttons aligned at the bottom); stacked on phones (grid auto-fit).
8. 429: `Alert` shows messages starting with "Too many attempts." with `alert-warning` (amber) and the same `role="alert"` and text. Pages only keep `error.message`, so detection is by that fixed text from `api/client.js` rather than by `ApiError.status` (avoids touching every page). New `tests/alert.test.jsx` (2 tests). Weakness: depends on the message prefix.
9. Form widths: `--form-width` token (480px); `.narrow` uses it with `width: 100%` (full width on phones). Transfer, Deposits form, Wallet lookup, Admin deposit already used `.narrow`; Profile cards remain a two-column grid by design. Nothing else changed.
10. Phone nav: one horizontally scrollable row (`overflow-x: auto`, no wrap, hidden scrollbar, `flex: none` links); `NavBar` scrolls the active link to the centre on route change (only if overflowing). Justification: avoids 2-3 rows eating the screen; the Admin link is last so may need a swipe. Weakness: no visible scroll hint.

### Re-check visually
- Desktop 1280: Admin > Deposit requests (Approve + Reject visible, pinned column shadow when scrolled, note ellipsis + tooltip, wider column), Reject flow (form above table, row highlighted, red Confirm reject, Cancel), Admin > Users (badges, buttons), Admin > All transactions, other pages stay 1040px.
- Phone 375: nav row scrolling and active link position; Admin deposit requests and Users as cards (Approve/Reject/Disable/Enable full width, disabled self row); reject form + highlighted card; History and Admin All transactions with the Filters toggle (collapsed, opened, Apply/Reset, caret); "Loading your account..." (reload with a token); Dashboard balance card with hint; Profile stacked; Transfer/Deposits/Lookup/Admin deposit cards full width; no page-level horizontal scroll; 429 amber alert on login and forms.
- Reduced motion: caret transition and spinner still disabled.
