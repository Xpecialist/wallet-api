package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.WalletApiApplication;
import com.example.wallet.config.SchemaShapeGuard;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Flyway owns the schema. Works only on {@code walletdb_test} (TestDbGuard checks the URL and the live connection
 * before anything is cleaned). Every test starts from an empty database; afterwards the database is cleaned and
 * migrated again for the other integration tests.
 */
class FlywayMigrationIT {
    private static final String URL = "jdbc:postgresql://localhost:5432/" + TestDbGuard.TEST_DB;
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");
    private static final List<String> TABLES = List.of("users", "wallets", "transactions", "deposit_requests");

    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() throws Exception {
        TestDbGuard.assertTestUrl(URL);
        ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        try (Connection c = ds.getConnection()) {
            TestDbGuard.assertConnectedToTestDb(c);
        }
        jdbc = new JdbcTemplate(ds);
        TestDbGuard.cleanTestDatabase(URL, USER, PASSWORD);
    }

    @AfterEach
    void restoreMigratedDatabase() {
        TestDbGuard.cleanTestDatabase(URL, USER, PASSWORD);
        flyway().migrate();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").cleanDisabled(false).load();
    }

    private void run(String classpathScript) {
        new ResourceDatabasePopulator(new ClassPathResource(classpathScript)).execute(ds);
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private boolean tableExists(String name) {
        return count("select count(*) from pg_tables where schemaname=current_schema() and tablename='" + name + "'")
                == 1;
    }

    private ConfigurableApplicationContext startApp() {
        return new SpringApplicationBuilder(WalletApiApplication.class)
                .properties("spring.profiles.active=it", "spring.datasource.url=" + URL, "server.port=0",
                        "spring.main.banner-mode=off")
                .run();
    }

    private void insertSomeData() {
        jdbc.update("insert into users(id,username,email,password_hash) values "
                + "(1,'alice','alice@example.com','h'),(2,'bob','bob@example.com','h'),(3,'dave','dave@example.com','h')");
        jdbc.update("insert into wallets(user_id,balance) values (1,100),(2,100),(3,100)");
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (1,2,5,'TRANSFER','COMPLETED','k1'),(2,1,5,'TRANSFER','COMPLETED','k2')");
        jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (1,7,'DEPOSIT','COMPLETED','d1')");
    }

    // ---- (a) empty database ----------------------------------------------------------------------------------

    @Test
    void emptyDatabaseIsMigratedToTheFullSchemaByRunningV1() {
        for (String t : TABLES) assertThat(tableExists(t)).as("empty before: " + t).isFalse();

        MigrateResult r = flyway().migrate();

        assertThat(r.success).isTrue();
        assertThat(r.migrationsExecuted).isEqualTo(2);
        for (String t : TABLES) assertThat(tableExists(t)).as("created: " + t).isTrue();
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(jdbc.queryForObject("select type from flyway_schema_history where version='1'", String.class))
                .as("V1 really ran, it was not just baselined").isEqualTo("SQL");
        SchemaShapeGuard.verifyCurrent(jdbc, "shape");
    }

    @Test
    void applicationStartsOnAnEmptyDatabaseAndValidatesTheMigratedSchema() {
        try (ConfigurableApplicationContext ctx = startApp()) {
            assertThat(ctx.isRunning()).isTrue();
        }
        assertThat(jdbc.queryForObject("select type from flyway_schema_history where version='1'", String.class))
                .isEqualTo("SQL");
        assertThat(count("select count(*) from flyway_schema_history")).isEqualTo(2);
    }

    // ---- (b) existing database with the current schema and no history ----------------------------------------

    @Test
    void existingCurrentDatabaseWithoutHistoryIsBaselinedAndKeepsItsData() {
        run("db/migration/V1__baseline.sql"); // what the old schema.sql left behind on a current database
        insertSomeData();
        assertThat(tableExists("flyway_schema_history")).isFalse();

        try (ConfigurableApplicationContext ctx = startApp()) {
            assertThat(ctx.isRunning()).as("started, so Hibernate validate passed").isTrue();
        }

        assertThat(count("select count(*) from flyway_schema_history")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select type from flyway_schema_history where version='1'", String.class)).isEqualTo("BASELINE");
        assertThat(jdbc.queryForObject("select type from flyway_schema_history where version='2'", String.class)).isEqualTo("SQL");
        assertThat(count("select count(*) from users where token_version = 0")).as("existing rows start at 0").isEqualTo(3);
        assertThat(count("select count(*) from users")).isEqualTo(3);
        assertThat(count("select count(*) from wallets")).isEqualTo(3);
        assertThat(count("select count(*) from transactions")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(balance) from wallets", BigDecimal.class))
                .isEqualByComparingTo("300");
    }

    @Test
    void databaseOlderThanTheCurrentShapeIsRefusedAndNotBaselined() {
        run("old-schema.sql"); // pre-round-1 shape: no lower() indexes, no per-owner key, no deposit_requests
        insertSomeData();

        assertThatThrownBy(this::startApp).hasStackTraceContaining("Refusing to baseline")
                .hasStackTraceContaining("missing table deposit_requests")
                .hasStackTraceContaining("missing index uq_users_username_lower");

        assertThat(tableExists("flyway_schema_history")).as("nothing was written").isFalse();
        assertThat(count("select count(*) from users")).isEqualTo(3);
        assertThat(count("select count(*) from transactions")).isEqualTo(3);
    }

    @Test
    void shapeGuardNamesWhatIsMissingWhenAnIndexOrConstraintWasDropped() {
        flyway().migrate();
        SchemaShapeGuard.verify(jdbc, "shape");
        jdbc.execute("drop index uq_tx_owner_idempotency");
        jdbc.execute("alter table transactions drop constraint chk_tx_type_wallets");

        assertThatThrownBy(() -> SchemaShapeGuard.verify(jdbc, "Drifted"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing index uq_tx_owner_idempotency")
                .hasMessageContaining("missing constraint chk_tx_type_wallets");
    }

    @Test
    void v1OnlyDatabaseWithDataMigratesToV2KeepingEverythingAndStartingTokenVersionsAtZero() {
        Flyway v1 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("1").load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        insertSomeData();
        assertThat(count("select count(*) from information_schema.columns where table_name='users' and column_name='token_version'"))
                .as("V1 has no token_version").isZero();
        SchemaShapeGuard.verify(jdbc, "V1 shape");

        MigrateResult r = flyway().migrate();

        assertThat(r.success).isTrue();
        assertThat(r.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank",
                String.class)).containsExactly("1", "2");
        assertThat(count("select count(*) from users")).isEqualTo(3);
        assertThat(count("select count(*) from wallets")).isEqualTo(3);
        assertThat(count("select count(*) from transactions")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(balance) from wallets", BigDecimal.class)).isEqualByComparingTo("300");
        assertThat(count("select count(*) from users where token_version = 0")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select data_type || '/' || is_nullable || '/' || column_default from "
                + "information_schema.columns where table_name='users' and column_name='token_version'", String.class))
                .isEqualTo("bigint/NO/0");
        jdbc.update("insert into users(id,username,email,password_hash) values (100,'erin','erin@example.com','h')");
        assertThat(count("select token_version from users where username='erin'")).as("default for new rows").isZero();
        SchemaShapeGuard.verifyCurrent(jdbc, "current shape");
        try (ConfigurableApplicationContext ctx = startApp()) {
            assertThat(ctx.isRunning()).as("Hibernate validate passes on the migrated database").isTrue();
        }
    }

    @Test
    void currentShapeCheckNamesAMissingTokenVersionColumnWhileTheV1CheckStillPasses() {
        flyway().migrate();
        jdbc.execute("alter table users drop column token_version");

        SchemaShapeGuard.verify(jdbc, "V1 shape");
        assertThatThrownBy(() -> SchemaShapeGuard.verifyCurrent(jdbc, "Drifted"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing column users.token_version");
    }

    // ---- (c) repeated migration ------------------------------------------------------------------------------

    @Test
    void migratingTwiceIsANoOp() {
        flyway().migrate();
        long indexes = count("select count(*) from pg_indexes where schemaname=current_schema()");
        long constraints = count("select count(*) from pg_constraint");
        jdbc.update("insert into users(username,email,password_hash) values ('alice','alice@example.com','h')");

        MigrateResult second = flyway().migrate();

        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(count("select count(*) from flyway_schema_history")).isEqualTo(2);
        assertThat(count("select count(*) from pg_indexes where schemaname=current_schema()")).isEqualTo(indexes);
        assertThat(count("select count(*) from pg_constraint")).isEqualTo(constraints);
        assertThat(count("select count(*) from users")).as("data untouched").isEqualTo(1);
    }

    @Test
    void applicationRestartOnAMigratedDatabaseChangesNothing() {
        try (ConfigurableApplicationContext ctx = startApp()) { assertThat(ctx.isRunning()).isTrue(); }
        try (ConfigurableApplicationContext ctx = startApp()) { assertThat(ctx.isRunning()).isTrue(); }
        assertThat(count("select count(*) from flyway_schema_history")).isEqualTo(2);
    }

    // ---- (d) schema shape ------------------------------------------------------------------------------------

    @Test
    void migratedSchemaHasTheExpectedTablesIndexesAndConstraints() {
        flyway().migrate();

        for (String t : TABLES) assertThat(tableExists(t)).isTrue();
        for (String i : List.of("uq_users_username_lower", "uq_users_email_lower", "idx_tx_sender", "idx_tx_receiver",
                "idx_tx_created_at", "uq_tx_owner_idempotency", "idx_dr_wallet", "idx_dr_status",
                "uq_dr_wallet_idempotency", "uq_dr_transaction")) {
            assertThat(count("select count(*) from pg_indexes where schemaname=current_schema() and indexname='"
                    + i + "'")).as("index " + i).isEqualTo(1);
        }
        for (String c : List.of("chk_tx_type_wallets", "chk_dr_decision", "wallets_balance_check",
                "transactions_amount_check", "deposit_requests_amount_check", "deposit_requests_status_check")) {
            assertThat(count("select count(*) from pg_constraint where conname='" + c + "'")).as(c).isEqualTo(1);
        }
        assertThat(count("select count(*) from pg_constraint where conrelid='transactions'::regclass "
                + "and contype='u'")).as("no global unique key on idempotency_key").isZero();
        assertThat(count("select count(*) from pg_constraint where contype='f' and conrelid in "
                + "('wallets'::regclass,'transactions'::regclass,'deposit_requests'::regclass)"))
                .as("foreign keys: wallets 1, transactions 2, deposit_requests 4").isEqualTo(7);
        assertThat(count("select count(*) from information_schema.columns where table_schema=current_schema() "
                + "and table_name='transactions' and column_name in ('sender_wallet_id','receiver_wallet_id') "
                + "and is_nullable='YES'")).isEqualTo(2);
    }

    @Test
    void migratedSchemaEnforcesPerOwnerKeysCaseInsensitiveUsersAndDepositRequestRules() {
        flyway().migrate();
        insertSomeData();

        // k1 belongs to wallet 1 (sender): wallet 1 cannot reuse it, wallet 3 can
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,"
                + "status,idempotency_key) values (1,2,1,'TRANSFER','COMPLETED','k1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (3,2,1,'TRANSFER','COMPLETED','k1')");
        // a deposit is owned by its receiver
        assertThatThrownBy(() -> jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,"
                + "idempotency_key) values (1,1,'DEPOSIT','COMPLETED','d1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (2,1,'DEPOSIT','COMPLETED','d1')");
        // type and wallet ids must agree
        assertThatThrownBy(() -> jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status) "
                + "values (1,1,'TRANSFER','COMPLETED')")).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('ALICE','other@example.com','h')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('zed','Alice@Example.com','h')")).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into deposit_requests(wallet_id,user_id,amount,idempotency_key) values (1,1,5,'k')");
        assertThat(jdbc.queryForObject("select status from deposit_requests", String.class)).isEqualTo("PENDING");
        assertThatThrownBy(() -> jdbc.update(
                "insert into deposit_requests(wallet_id,user_id,amount) values (1,1,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into deposit_requests(wallet_id,user_id,amount,status) values (1,1,5,'BOGUS')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into deposit_requests(wallet_id,user_id,amount,idempotency_key) values (1,1,5,'k')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into deposit_requests(wallet_id,user_id,amount,idempotency_key) values (2,2,5,'k')");
        assertThatThrownBy(() -> jdbc.update("update deposit_requests set status='APPROVED' where wallet_id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update deposit_requests set status='APPROVED', decided_at=now(), decided_by=3, transaction_id=1 "
                + "where wallet_id=1");
        assertThatThrownBy(() -> jdbc.update("update deposit_requests set status='APPROVED', decided_at=now(), "
                + "decided_by=3, transaction_id=1 where wallet_id=2")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- configuration ---------------------------------------------------------------------------------------

    @Test
    void mainConfigurationDisablesCleanBaselinesAtV1AndHasNoSchemaSql() throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) { p.load(in); }
        assertThat(p.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.baseline-version")).isEqualTo("1");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p.getProperty("spring.sql.init.mode")).isNull();
        assertThat(new ClassPathResource("schema.sql").exists()).isFalse();
        assertThat(new ClassPathResource("db/migration/V1__baseline.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V2__user_token_version.sql").exists()).isTrue();
    }
}
