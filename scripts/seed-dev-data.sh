#!/usr/bin/env bash
# Fills a FRESH, running dev backend with demo users and activity through the real API.
# Needs: backend on $API (default http://localhost:8080) started with an admin
# (ADMIN_PASSWORD set), and bash + curl. Run it in Git Bash:
#   ADMIN_PASSWORD='<the admin password>' bash scripts/seed-dev-data.sh
# Demo users all share $SEED_PASSWORD (dev only, change it if you like).
set -u
API="${API:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USER:-admin}"
SEED_PASSWORD="${SEED_PASSWORD:-SeedUser#2026}"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD to the admin password}"
USERS=(alice bob carol dave erin)

json() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }
call() { # method path token [body] -> prints body, sets HTTP code in $CODE
  local out
  out=$(curl -s -w '\n%{http_code}' -X "$1" "$API$2" -H "Authorization: Bearer $3" \
        -H 'Content-Type: application/json' ${4:+-d "$4"})
  CODE=${out##*$'\n'}; printf '%s' "${out%$'\n'*}"
}
login() { curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
          -d "{\"username\":\"$1\",\"password\":\"$2\"}" | json token; }

echo "== admin login"
ADMIN_TOKEN=$(login "$ADMIN_USER" "$ADMIN_PASSWORD")
[ -n "$ADMIN_TOKEN" ] || { echo "admin login failed (is ADMIN_PASSWORD right and the backend up?)"; exit 1; }

declare -A TOKEN WALLET
echo "== registering demo users"
for u in "${USERS[@]}"; do
  curl -s -o /dev/null -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"email\":\"$u@example.com\",\"password\":\"$SEED_PASSWORD\"}"
  TOKEN[$u]=$(login "$u" "$SEED_PASSWORD")
  [ -n "${TOKEN[$u]}" ] || { echo "login failed for $u"; exit 1; }
  WALLET[$u]=$(call GET /api/wallets/me "${TOKEN[$u]}" | json id)
  echo "  $u wallet=${WALLET[$u]}"
done

echo "== admin deposits (the only way money enters)"
amounts=(1000 750 500 300 200)
for i in "${!USERS[@]}"; do
  u=${USERS[$i]}
  call POST "/api/admin/wallets/${WALLET[$u]}/deposit" "$ADMIN_TOKEN" "{\"amount\":${amounts[$i]}}" >/dev/null
  echo "  deposit ${amounts[$i]} -> $u: HTTP $CODE"
done

echo "== transfers"
transfer() { call POST /api/transactions/transfer "${TOKEN[$1]}" "{\"receiverUsername\":\"$2\",\"amount\":$3,\"note\":\"$4\"}" >/dev/null
             echo "  $1 -> $2 $3 ($4): HTTP $CODE"; }
transfer alice bob 120.50 "dinner"
transfer alice carol 45 "movie tickets"
transfer bob dave 80 "rent share"
transfer carol erin 25.25 "coffee"
transfer dave alice 60 "paid back"
transfer erin bob 30 "lunch"
transfer bob alice 15.75 "snacks"
transfer carol dave 100 "birthday gift"

echo "== withdrawals"
for pair in "alice 50" "bob 20" "erin 10.5"; do
  set -- $pair
  call POST /api/wallets/me/withdraw "${TOKEN[$1]}" "{\"amount\":$2}" >/dev/null
  echo "  $1 withdraw $2: HTTP $CODE"
done

echo "== done. Demo logins: ${USERS[*]} / \$SEED_PASSWORD"
