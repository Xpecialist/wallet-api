#!/usr/bin/env bash
# Fills a running backend with a rich, realistic DEMO dataset through the real API.
# Re-runnable: every money action uses a fixed Idempotency-Key, so a second run replays
# instead of duplicating (see SEED_DATA_LOG.md). Needs bash + curl + sed/grep/awk (Git Bash is fine).
#
#   ADMIN_PASSWORD='<the admin password>' bash scripts/seed-dev-data.sh
#
# Environment:
#   API               base URL (default http://localhost:8080); non-localhost needs SEED_ALLOW_REMOTE=1
#   ADMIN_USER        admin username (default admin)
#   ADMIN_PASSWORD    REQUIRED, no default, never printed
#   SEED_PASSWORD     password of the demo users (default below is DEV ONLY, change it for any shared demo)
#   SEED_ALLOW_REMOTE set to 1 to allow a non-localhost API
#   SEED_PACE         seconds to sleep after each request (default 0.2)
#
# With the default rate limits (register: 5 per hour per IP) a fresh database cannot be seeded in one go
# (6 new users). Then restart the backend ONCE for seeding without limits and restart it normally afterwards:
#   mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--app.rate-limit.enabled=false"
set -u
API="${API:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USER:-admin}"
SEED_PASSWORD="${SEED_PASSWORD:-SeedUser#2026}"   # DEV-ONLY demo default
PACE="${SEED_PACE:-0.2}"
MAX_WAIT=180        # a Retry-After above this many seconds aborts instead of waiting
MAX_ATTEMPTS=6
KEY_PREFIX="seed-v2"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the admin password (it is never printed)}"

case "$API" in
  http://localhost|http://localhost[:/]*|http://127.0.0.1|http://127.0.0.1[:/]*|https://localhost|https://localhost[:/]*|http://\[::1\]|http://\[::1\][:/]*) ;;
  *) if [ "${SEED_ALLOW_REMOTE:-}" != "1" ]; then
       echo "Refusing to seed non-local API '$API'. Set SEED_ALLOW_REMOTE=1 if you really mean it."; exit 1
     fi ;;
esac

TMP=$(mktemp -d) || { echo "cannot create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT

die() { echo "ERROR: $*" >&2; exit 1; }

# json_get <key> : first value of "key" in $OUT (string or number), empty if absent
json_get() { printf '%s' "$OUT" | grep -o "\"$1\":\"\{0,1\}[^\",}]*" | head -1 | sed 's/^[^:]*:"\{0,1\}//'; }

abort_rate_limit() {
  cat >&2 <<EOF

ERROR: rate limit hit with Retry-After=$1 s (more than ${MAX_WAIT} s), not waiting.
The registration limit (5 per hour per IP) cannot be seeded through quickly. Stop the backend and
restart it ONCE for seeding with the rate limit switched off:

  mvn spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--app.rate-limit.enabled=false"

Run this script again (it continues where it stopped), then restart the backend normally.
EOF
  exit 2
}

# req METHOD PATH TOKEN [BODY] [IDEMPOTENCY_KEY]  ->  sets CODE and OUT. Waits and retries on 429.
req() {
  local m=$1 p=$2 t=$3 b=${4-} k=${5-} attempt=0 ra
  while :; do
    attempt=$((attempt + 1))
    local args=(-s -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$m" "$API$p")
    [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
    [ -n "$k" ] && args+=(-H "Idempotency-Key: $k")
    if [ -n "$b" ]; then
      CODE=$(printf '%s' "$b" | curl "${args[@]}" -H 'Content-Type: application/json' --data-binary @-)
    else
      CODE=$(curl "${args[@]}" </dev/null)
    fi
    OUT=$(cat "$TMP/body" 2>/dev/null)
    if [ "$CODE" = "429" ]; then
      ra=$(grep -i '^retry-after:' "$TMP/hdr" | head -1 | tr -d '\r' | awk '{print $2}')
      case "$ra" in ''|*[!0-9]*) ra=10 ;; esac
      [ "$ra" -gt "$MAX_WAIT" ] && abort_rate_limit "$ra"
      [ "$attempt" -ge "$MAX_ATTEMPTS" ] && die "still rate limited after $MAX_ATTEMPTS attempts ($m $p)"
      ra=$((ra + 1))
      echo "  waiting ${ra}s (rate limit)"
      sleep "$ra"
      continue
    fi
    sleep "$PACE"
    return 0
  done
}

# ok_or_die LABEL : accept any 2xx
ok_or_die() {
  case "$CODE" in
    2??) return 0 ;;
    000) die "$1: cannot reach $API" ;;
    *) die "$1: HTTP $CODE ${OUT:0:200}" ;;
  esac
}

login() { # user password -> TOKEN_OUT ('' on failure, CODE set)
  req POST /api/auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}"
  TOKEN_OUT=""
  [ "$CODE" = "200" ] && TOKEN_OUT=$(json_get token)
  return 0
}

# ---------------------------------------------------------------- preflight
echo "== checking API and admin login ($API)"
login "$ADMIN_USER" "$ADMIN_PASSWORD"
case "$CODE" in
  200) ;;
  000) die "API not reachable at $API (is the backend running?)" ;;
  401) die "admin login failed (wrong ADMIN_USER / ADMIN_PASSWORD?)" ;;
  *)   die "admin login: unexpected HTTP $CODE ${OUT:0:200}" ;;
esac
ADMIN_TOKEN=$TOKEN_OUT
[ -n "$ADMIN_TOKEN" ] || die "admin login returned no token"
echo "  admin login OK"
echo "  NOTE: demo users use the password from SEED_PASSWORD (default is a dev-only value). Change it for any shared demo."

# ---------------------------------------------------------------- users
USERS=(alice bob carol dave erin frank grace heidi ivan judy mallory)
declare -A TOKEN WALLET SKIP
MALLORY_DISABLED=0

admin_users_page() { req GET "/api/admin/users?size=100&sort=id" "$ADMIN_TOKEN"; ok_or_die "list users"; }

echo "== users"
for u in "${USERS[@]}"; do
  login "$u" "$SEED_PASSWORD"
  if [ -z "$TOKEN_OUT" ]; then
    if [ "$CODE" = "401" ]; then
      req POST /api/auth/register "" "{\"username\":\"$u\",\"email\":\"$u@example.com\",\"password\":\"$SEED_PASSWORD\"}"
      case "$CODE" in
        201|200) echo "  registered $u"; login "$u" "$SEED_PASSWORD" ;;
        409) ;;   # exists: handled below
        *) die "register $u: HTTP $CODE ${OUT:0:200}" ;;
      esac
    else
      die "login $u: HTTP $CODE ${OUT:0:200}"
    fi
  fi
  if [ -z "$TOKEN_OUT" ]; then
    # exists but cannot log in: acceptable only when the admin already disabled it (mallory)
    admin_users_page
    if printf '%s' "$OUT" | grep -o "\"username\":\"$u\"[^}]*" | grep -q '"enabled":false'; then
      SKIP[$u]=1; [ "$u" = "mallory" ] && MALLORY_DISABLED=1
      echo "  $u is disabled already, skipping its activity"
      continue
    fi
    die "user $u exists but login fails: it was created with a different password (SEED_PASSWORD)"
  fi
  TOKEN[$u]=$TOKEN_OUT
  req GET /api/wallets/me "${TOKEN[$u]}"; ok_or_die "wallet of $u"
  WALLET[$u]=$(json_get id)
  [ -n "${WALLET[$u]}" ] || die "no wallet id for $u"
  echo "  $u ready (wallet ${WALLET[$u]})"
done

skipped() { [ -n "${SKIP[$1]:-}" ]; }

# ---------------------------------------------------------------- admin deposits
echo "== admin deposits"
deposit() { # user amount
  skipped "$1" && return 0
  req POST "/api/admin/wallets/${WALLET[$1]}/deposit" "$ADMIN_TOKEN" "{\"amount\":$2}" "$KEY_PREFIX-deposit-$1"
  ok_or_die "deposit $1"; echo "  deposit $2 -> $1"
}
deposit alice 5000.00;  deposit bob 3200.50;   deposit carol 2750.75; deposit dave 1800.00
deposit erin 1250.25;   deposit frank 900.00;  deposit grace 650.40;  deposit heidi 1500.00
deposit ivan 400.00;    deposit judy 300.00;   deposit mallory 275.50

# ---------------------------------------------------------------- transfers
echo "== transfers"
N=0
transfer() { # sender receiver amount note
  N=$((N + 1))
  skipped "$1" && return 0
  skipped "$2" && return 0
  local key; key=$(printf '%s-transfer-%03d' "$KEY_PREFIX" "$N")
  req POST /api/transactions/transfer "${TOKEN[$1]}" \
    "{\"receiverUsername\":\"$2\",\"amount\":$3,\"note\":\"$4\"}" "$key"
  ok_or_die "transfer #$N $1 -> $2"
  echo "  #$N $1 -> $2 $3 ($4)"
}
transfer alice bob 120.50 "Dinner at Luigi's"
transfer alice carol 45.00 "Movie tickets"
transfer bob dave 80.00 "Rent share"
transfer carol erin 25.25 "Coffee"
transfer dave alice 60.00 "Paid back"
transfer erin bob 30.00 "Lunch"
transfer bob alice 15.75 "Snacks"
transfer carol dave 100.00 "Birthday gift"
transfer alice frank 250.00 "Rent for November"
transfer bob grace 75.20 "Concert tickets"
transfer carol heidi 33.40 "Groceries"
transfer dave ivan 60.00 "Split bill"
transfer erin judy 18.90 "Coffee"
transfer alice heidi 140.00 "Concert tickets"
transfer bob frank 42.35 "Pizza night"
transfer frank grace 30.00 "Paid back"
transfer grace alice 20.00 "Taxi share"
transfer heidi bob 55.10 "Groceries"
transfer ivan carol 12.50 "Lunch"
transfer judy dave 9.75 "Coffee"
transfer alice judy 65.00 "Birthday gift"
transfer carol bob 210.00 "Rent share"
transfer dave erin 38.60 "Dinner"
transfer erin alice 72.45 "Groceries"
transfer heidi ivan 90.00 "Split bill"
transfer frank judy 25.00 "Movie tickets"
transfer grace heidi 15.30 "Coffee"
transfer alice dave 300.00 "Weekend trip"
transfer bob carol 48.80 "Paid back"
transfer mallory alice 20.00 "Lunch"
transfer alice mallory 35.00 "Board game night"
transfer carol frank 61.25 "Groceries"
transfer dave bob 27.90 "Snacks"
transfer heidi erin 120.00 "Birthday gift"
transfer ivan judy 8.40 "Coffee"
transfer frank alice 45.00 "Dinner"
transfer judy bob 14.20 "Lunch"
transfer bob heidi 36.00 "Gym membership"
transfer erin carol 22.75 "Coffee"
transfer grace carol 41.10 "Concert tickets"
transfer mallory bob 15.00 "Paid back"

# ---------------------------------------------------------------- withdrawals
echo "== withdrawals"
withdraw() { # user amount
  skipped "$1" && return 0
  req POST /api/wallets/me/withdraw "${TOKEN[$1]}" "{\"amount\":$2}" "$KEY_PREFIX-withdraw-$1"
  ok_or_die "withdraw $1"; echo "  withdraw $2 <- $1"
}
withdraw alice 100.00; withdraw bob 250.00; withdraw carol 80.50; withdraw dave 60.00
withdraw frank 120.00; withdraw heidi 200.00; withdraw ivan 50.00; withdraw grace 30.00

# ---------------------------------------------------------------- deposit requests
echo "== deposit requests"
declare -A REQID
request() { # user amount note
  skipped "$1" && return 0
  req POST /api/deposit-requests "${TOKEN[$1]}" "{\"amount\":$2,\"note\":\"$3\"}" "$KEY_PREFIX-request-$1-1"
  ok_or_die "deposit request $1"
  REQID[$1]=$(json_get id)
  [ -n "${REQID[$1]}" ] || die "no request id for $1"
  echo "  request $2 from $1 (id ${REQID[$1]})"
}
request alice 500.00 "Bank transfer ref ALC-2026-11"
request dave 250.00 "Top up for the month"
request judy 120.75 "Cash deposit at branch"
request frank 300.00 "Salary top up"
request heidi 450.50 "Bank transfer ref HDI-4471"
request erin 200.00 "Savings top up"
request grace 1000.00 "Bank transfer ref GRC-0098"
request ivan 75.00 "Card top up"

decide() { # approve|reject user note
  local id=${REQID[$2]:-}
  [ -n "$id" ] || return 0
  req POST "/api/admin/deposit-requests/$id/$1" "$ADMIN_TOKEN" "{\"decisionNote\":\"$3\"}"
  case "$CODE" in
    2??) echo "  decision $1 recorded for $2" ;;
    409) echo "  request of $2 already decided, skipped" ;;
    *) die "$1 request of $2: HTTP $CODE ${OUT:0:200}" ;;
  esac
}
echo "== decisions"
decide approve frank "Bank transfer received"
decide approve heidi "Payment confirmed"
decide approve erin "Funds received, thank you"
decide reject grace "Payment not received"
decide reject ivan "Reference does not match any payment"

# ---------------------------------------------------------------- disable mallory
echo "== disabling mallory"
if [ "$MALLORY_DISABLED" = "1" ]; then
  echo "  mallory already disabled"
else
  admin_users_page
  MID=$(printf '%s' "$OUT" | grep -o '"id":[0-9]*,"username":"mallory"' | head -1 | sed 's/^"id":\([0-9]*\).*/\1/')
  [ -n "$MID" ] || die "mallory not found in the user list"
  req PATCH "/api/admin/users/$MID/status" "$ADMIN_TOKEN" '{"enabled":false}'
  ok_or_die "disable mallory"; echo "  mallory disabled"
fi

# ---------------------------------------------------------------- summary
total() { json_get totalElements; }
echo
echo "== summary"
req GET "/api/admin/users?size=1" "$ADMIN_TOKEN"; ok_or_die "count users"; echo "  users:                $(total)"
req GET "/api/admin/transactions?size=1" "$ADMIN_TOKEN"; ok_or_die "count transactions"; echo "  transactions:         $(total)"
for s in PENDING APPROVED REJECTED; do
  req GET "/api/admin/deposit-requests?status=$s&size=1" "$ADMIN_TOKEN"; ok_or_die "count $s"
  printf '  %-9s requests:   %s\n' "$s" "$(total)"
done
echo "  demo logins (username only): ${USERS[*]}"
echo "  password: the value of SEED_PASSWORD (default is a dev-only value; change it for any shared demo)"
echo "  mallory is disabled on purpose (admin Users page demo)."
echo "  re-running this script changes nothing (fixed Idempotency-Keys)."
exit 0
