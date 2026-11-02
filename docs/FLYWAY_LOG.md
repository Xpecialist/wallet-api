# Flyway migrations (finding M6), 2026-10-23

Branch `Testing`. Not committed. No secrets are written in this file.

## Design

- `schema.sql` (run on every start by `spring.sql.init.mode=always`, with drop/re-add of `chk_tx_type_wallets` each boot) is replaced by Flyway. Each migration runs once; history is in `flyway_schema_history`.
- `src/main/resources/db/migration/V1__baseline.sql`: CREATE-only baseline of the current final schema (no ALTER/DROP churn, no DO blocks, no whitespace-trim/collision upgrade logic).
- Config (`application.properties`): `spring.flyway.enabled=true`, `locations=classpath:db/migration`, `baseline-on-migrate=true`, `baseline-version=1`, `validate-on-migrate=true`, `clean-disabled=true`. `spring.sql.init.*` removed, `ddl-auto=validate` kept. `application-it.properties` sets `clean-disabled=false` (test profile only).
- Empty database: Flyway only baselines a NON-empty schema, so an empty database runs V1 in full (tested: history row type `SQL`, not `BASELINE`).
- Existing database with the current shape and no history: baselined at 1, V1 not re-run, data untouched.
- Safety guard `com.example.wallet.config.SchemaShapeGuard` (a `FlywayMigrationStrategy` bean): when `users` exists but `flyway_schema_history` does not (a baseline is about to happen) it checks the 4 tables, 10 indexes and 6 named constraints V1 defines, that `transactions` has no global UNIQUE constraint and that `sender_wallet_id`/`receiver_wallet_id` are nullable. If anything is missing it throws before Flyway writes anything, listing every missing item. The same check runs after every `migrate()`, so later drift (a dropped index or constraint) also stops startup. Hibernate validate alone would not catch missing indexes/constraints (it checks tables/columns/types).

## Dependency changes (pom.xml)

- `org.springframework.boot:spring-boot-starter-flyway` (managed, 4.1.2; brings `spring-boot-flyway`)
- `org.flywaydb:flyway-database-postgresql` (runtime scope, managed by the Boot BOM: 12.4.0; brings `flyway-core` 12.4.0)

No version numbers were added to the pom. Verified with `mvn dependency:tree` and a full build.

## Baseline verification (schema diff)

Two scratch databases (`walletdb_flyway_scratch_1` and `_2`, both dropped afterwards). The old `schema.sql` (from HEAD) was applied with psql to an empty #1, `V1__baseline.sql` to an empty #2, then `pg_dump --schema-only` (PostgreSQL 16) of both was diffed. Result: IDENTICAL; the only differing lines were the two random `\restrict` / `\unrestrict` tokens that pg_dump 16 emits on every run (403 lines each: tables, defaults, sequences, constraints with auto-generated names, indexes, FKs). Auto-generated names (e.g. `wallets_balance_check`, `users_username_key`) match because V1 uses the same inline definitions.
The old file remains in git history (`git show HEAD:src/main/resources/schema.sql`).

## Existing databases and the risk case

- The dev `walletdb` (and any database created by the previous code) is at the V1 shape, because the old script made it so on every start. On the first start with this code it is baselined at version 1 (the guard check runs first). I did NOT open `walletdb` to confirm, as instructed; the equivalent case is tested on `walletdb_test` (current schema without history, with data).
- Risk: a non-empty database OLDER than the current shape would be baselined as if current. Mitigation: the guard refuses it (tested with `old-schema.sql`, the pre-round-1 shape: startup fails with "Refusing to baseline ... missing table deposit_requests; missing index uq_users_username_lower ...", no history table is written, data untouched).
- Supported upgrade path (also in README): the database must already be at the current shape before Flyway adoption: start the previous version of the app once (its `schema.sql` upgrades old shapes), or recreate the database. Limit: the guard checks existence of named objects, not definitions beyond what Hibernate validates; a hand-modified database with all names present but different definitions would pass.

## Files changed

| File | Before | After |
|---|---|---|
| `pom.xml` | no Flyway | + spring-boot-starter-flyway, flyway-database-postgresql |
| `src/main/resources/schema.sql` | run on every start | deleted |
| `src/main/resources/db/migration/V1__baseline.sql` | none | new, CREATE-only baseline |
| `src/main/resources/application.properties` | `spring.sql.init.*` | `spring.flyway.*` (above); line endings of the file normalized to LF (git autocrlf) |
| `src/main/java/com/example/wallet/config/SchemaShapeGuard.java` | none | new, baseline safety guard |
| `src/test/resources/application-it.properties` | | + `spring.flyway.clean-disabled=false` |
| `src/test/java/.../it/TestDbGuard.java` | dropped 4 tables after the URL/connection checks | same checks, then Flyway clean via `cleanTestDatabase` (re-asserts the URL itself, so clean can never run elsewhere) |
| `src/test/java/.../it/SchemaUpgradeIT.java` | 7 tests upgrading old data with schema.sql | deleted (the schema.sql upgrade path no longer exists) |
| `src/test/java/.../it/FlywayMigrationIT.java` | none | new, 10 tests |
| `src/test/resources/old-schema.sql` | used by SchemaUpgradeIT | kept, now proves an old database is refused |
| `README.md` | schema.sql described | new section "Database schema (Flyway)"; data model, setup and structure updated |

## Tests (FlywayMigrationIT, walletdb_test only)

(a) empty DB migrates to the full schema (V1 ran as SQL) and the app starts on an empty DB with validate OK. (b) Current schema plus data without history is baselined (type BASELINE, version 1), the app starts, rows and balances preserved; an old-shape DB is refused and not baselined; the guard names a dropped index/constraint. (c) Migrating twice is a no-op (0 migrations, 1 history row, same index/constraint counts, data kept); an app restart changes nothing. (d) Shape test (tables, 10 indexes, 6 constraints, 7 FKs, no global unique, nullable wallet ids) plus the behaviour assertions carried over from SchemaUpgradeIT (per-owner idempotency, case-insensitive users, deposit_requests checks) and the tx type/wallet CHECK. Config test: clean disabled, baseline settings, no `spring.sql.init.mode`, no schema.sql on the classpath.
Dropped from SchemaUpgradeIT: only tests of the removed ALTER/trim/collision upgrade logic (data upgrade, key swap, whitespace trim, username/email clash abort); that logic no longer exists.

## Results

| Command | Result |
|---|---|
| `mvn test` | 221 unit tests, 0 failures |
| `mvn verify` | 221 unit + 154 integration (151 - 7 SchemaUpgradeIT + 10 FlywayMigrationIT), 0 failures, BUILD SUCCESS |

ERROR lines in the IT output (refused old database, JWT-secret and admin-bootstrap tests) are expected.

## Remaining risks / not verified

- The real dev `walletdb` was not opened (rule); its baseline is inferred from the scratch/test evidence. Take a backup before the first start.
- Two instances starting at once on a database that needs baselining: the pre-check is read-only and unlocked; Flyway locks once the history table exists.
- `spring.flyway.clean-disabled` is protected by configuration only; the test-side clean is protected by TestDbGuard.
- `validate-on-migrate` makes an edited V1 fail startup; intended.
- The dev app on port 8080 runs from `target/classes`; I deleted the stale `target/classes/schema.sql` (compile added `db/migration` there). The running process was not restarted; on its next restart it will baseline `walletdb`.
- pg_dump comparison covers PostgreSQL 16 only.
