package com.example.wallet.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Safety net around Flyway's baseline-on-migrate. A non-empty database without {@code flyway_schema_history} is
 * baselined at version 1 WITHOUT running V1, so it must already have the V1 shape. Hibernate's validate only checks
 * tables and columns, so this guard also checks the constraints and indexes the application relies on (idempotency,
 * case-insensitive uniqueness, CHECKs). It runs before the baseline (nothing is written if the shape is wrong) and again
 * after every migration, and stops startup with a list of what is missing. An empty database is not affected: V1 builds it.
 */
@Configuration(proxyBeanMethods = false)
public class SchemaShapeGuard {
    static final List<String> TABLES = List.of("users", "wallets", "transactions", "deposit_requests");
    static final List<String> INDEXES = List.of("uq_users_username_lower", "uq_users_email_lower",
            "idx_tx_sender", "idx_tx_receiver", "idx_tx_created_at", "uq_tx_owner_idempotency",
            "idx_dr_wallet", "idx_dr_status", "uq_dr_wallet_idempotency", "uq_dr_transaction");
    static final List<String> CONSTRAINTS = List.of("chk_tx_type_wallets", "chk_dr_decision", "wallets_balance_check",
            "transactions_amount_check", "deposit_requests_amount_check", "deposit_requests_status_check");

    @Bean
    FlywayMigrationStrategy schemaShapeGuardMigrationStrategy() {
        return flyway -> {
            JdbcTemplate jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            if (hasTable(jdbc, "users") && !hasTable(jdbc, "flyway_schema_history")) {
                verify(jdbc, "Refusing to baseline Flyway on an existing database that is not at the current schema");
            }
            flyway.migrate();
            verify(jdbc, "Database schema does not match the Flyway migrations");
        };
    }

    private static boolean hasTable(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass(quote_ident(current_schema()) || '.' || quote_ident(?)) is not null",
                Boolean.class, name));
    }

    /** Throws IllegalStateException listing every missing table, index or constraint. */
    public static void verify(JdbcTemplate jdbc, String headline) {
        List<String> problems = new ArrayList<>();
        for (String t : TABLES) {
            if (!hasTable(jdbc, t)) problems.add("missing table " + t);
        }
        for (String i : INDEXES) {
            if (jdbc.queryForObject("select count(*) from pg_indexes where schemaname = current_schema() "
                    + "and indexname = ?", Long.class, i) == 0) problems.add("missing index " + i);
        }
        for (String c : CONSTRAINTS) {
            if (jdbc.queryForObject("select count(*) from pg_constraint where conname = ? and connamespace = "
                    + "current_schema()::regnamespace", Long.class, c) == 0) problems.add("missing constraint " + c);
        }
        if (hasTable(jdbc, "transactions")) {
            if (jdbc.queryForObject("select count(*) from pg_constraint where conrelid = 'transactions'::regclass "
                    + "and contype = 'u'", Long.class) > 0) {
                problems.add("transactions still has the old global UNIQUE constraint");
            }
            if (jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = "
                    + "current_schema() and table_name = 'transactions' and column_name in "
                    + "('sender_wallet_id','receiver_wallet_id') and is_nullable = 'NO'", Long.class) > 0) {
                problems.add("transactions.sender_wallet_id/receiver_wallet_id must be nullable");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(headline + ": " + String.join("; ", problems) + ". Databases must be at the"
                    + " current (V1) shape before Flyway adoption, or be recreated. See README, section Database schema.");
        }
    }
}
