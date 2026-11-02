# Run the demo yourself (about 10 minutes)

A local demo with realistic data: 12 users, about 60 transactions, deposit requests in every state. Nothing is hosted; everything runs on your machine.

## You need

- **JDK 17**, **Maven 3.9+**
- **Node 20.19+ or 22.12+** (with npm)
- **PostgreSQL 16** running on `localhost:5432`
- **bash and curl** (macOS/Linux have them; on Windows use Git Bash)

## 1. Get the code and create the database

```bash
git clone https://github.com/Xpecialist/wallet-api.git
cd wallet-api
psql -U postgres -h localhost -c "CREATE DATABASE walletdb"
```

The app creates all tables itself on first start (Flyway migrations).

> The `dev` profile assumes the database user `postgres` with password `postgres`. If yours differs, set `DB_USERNAME` and `DB_PASSWORD` before starting the backend.

## 2. Start the backend (first run: rate limits off for seeding)

Choose your own passwords (the admin password needs at least 12 characters). They are only used on your machine.

```bash
export ADMIN_PASSWORD='choose-a-long-admin-password'
export SEED_PASSWORD='choose-a-demo-user-password'
mvn spring-boot:run -Dspring-boot.run.profiles=dev "-Dspring-boot.run.arguments=--app.rate-limit.enabled=false"
```

PowerShell: use `$env:ADMIN_PASSWORD = '...'` and `$env:SEED_PASSWORD = '...'` instead of `export`.

Leave it running. It is ready when the log shows `Started WalletApiApplication`. (Rate limits are switched off only so the seeder can create 11 users at once; registration is normally capped at 5 per hour.)

## 3. Load the demo data (new terminal, same folder)

```bash
export ADMIN_PASSWORD='the same admin password'
export SEED_PASSWORD='the same demo-user password'
bash scripts/seed-dev-data.sh
```

It is safe to run again; it changes nothing the second time. Then **stop the backend (Ctrl+C) and start it again normally**, so you can see the rate limits work:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## 4. Start the frontend (another terminal)

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

## 5. Five-minute tour

Log in as `alice` (password = your `SEED_PASSWORD`). Other demo users: `bob`, `carol`, `dave`, `erin`, `frank`, `grace`, `heidi`, `ivan`, `judy`. The admin is `admin` (your `ADMIN_PASSWORD`).

1. **Dashboard and history.** Alice's balance and latest transactions. Open **History**, filter by type or date, and page through (about 15 transactions, so two pages).
2. **Transfer.** Send 10 to `bob` with a note. Try sending more than the balance and read the error.
3. **Deposit request.** Open **Deposits**, request 100 with a reference. It stays **PENDING**; money is never added automatically. Log out.
4. **Admin approval.** Log in as `admin` and open **Admin, Deposit requests**. Approve alice's request (or reject one with a reason). Log back in as alice: the balance rose and the deposit is in her history.
5. **Admin users.** The **Users** tab shows roles and status, including `mallory`, who is disabled on purpose.
6. **Rate limit.** On the login page, enter a wrong password six times quickly: you get "Too many attempts. Try again in N seconds."

## 6. Optional: try to break it

These use the backend directly (it must be running with rate limits **on**, as in step 3).

**Rate limit survives URL tricks** (this was a real bug we found and fixed): eight wrong logins through an encoded path, expect `401` five times, then `429`:

```bash
for i in 1 2 3 4 5 6 7 8; do
  curl -s -o /dev/null -w '%{http_code} ' -X POST 'http://localhost:8080/%61pi/auth/login' \
    -H 'Content-Type: application/json' -d '{"username":"nobody","password":"wrong-password-1"}'
done; echo
```

**Logout kills the token everywhere** (wait a minute first if the previous test rate-limited you):

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"alice\",\"password\":\"$SEED_PASSWORD\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -w 'before logout: %{http_code}\n' http://localhost:8080/api/wallets/me -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w 'logout: %{http_code}\n' -X POST http://localhost:8080/api/auth/logout -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w 'after logout: %{http_code}\n' http://localhost:8080/api/wallets/me -H "Authorization: Bearer $TOKEN"
```

Expected: `200`, `204`, then `401`.

## 7. Run the automated tests (optional, a few minutes)

```bash
psql -U postgres -h localhost -c "CREATE DATABASE walletdb_test"
mvn verify                      # 310 unit + 258 integration tests (uses only walletdb_test)
cd frontend && npm test         # 279 frontend tests
```

The integration tests wipe and rebuild `walletdb_test` on every run and refuse to run against any other database.

## Reset

Stop the backend, `DROP DATABASE walletdb`, create it again and repeat from step 2.

## Read more

The story of how the project was built and checked: [CASE_STUDY.md](CASE_STUDY.md). API and design details: [README.md](README.md).
