package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Runs the current {@code schema.sql} (through Spring's own script runner, like {@code spring.sql.init}) against a
 * database created with the OLD schema and filled with data. Works only on {@code walletdb_test}; the guard aborts
 * for any other database. Afterwards the tables are rebuilt with the current schema for the other integration tests.
 */
class SchemaUpgradeIT {
    private static final String URL = "jdbc:postgresql://localhost:5432/" + TestDbGuard.TEST_DB;
    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void oldDatabase() throws Exception {
        TestDbGuard.assertTestUrl(URL);
        ds = new DriverManagerDataSource(URL, env("DB_USERNAME", "postgres"), env("DB_PASSWORD", "postgres"));
        try (Connection c = ds.getConnection()) {
            TestDbGuard.assertConnectedToTestDb(c);
        }
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS deposit_requests, transactions, wallets, users CASCADE");
        run("old-schema.sql");
        jdbc.update("insert into users(id,username,email,password_hash) values "
                + "(1,'alice','alice@example.com','h'),(2,'bob','bob@example.com','h'),(3,'dave ','dave@example.com ','h')");
        jdbc.update("insert into wallets(user_id,balance) values (1,100),(2,100),(3,100)");
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (1,2,5,'TRANSFER','COMPLETED','k1'),(2,1,5,'TRANSFER','COMPLETED','k2')");
        jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (1,7,'DEPOSIT','COMPLETED','d1')");
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status) "
                + "values (1,2,1,'TRANSFER','COMPLETED')");
    }

    @AfterEach
    void rebuildCurrentSchema() {
        jdbc.execute("DROP TABLE IF EXISTS deposit_requests, transactions, wallets, users CASCADE");
        run("schema.sql");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private void run(String classpathScript) {
        new ResourceDatabasePopulator(new ClassPathResource(classpathScript)).execute(ds);
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }

    @Test
    void oldDatabaseReallyHasTheGlobalUniqueKeyAndNoCaseInsensitiveIndexes() {
        assertThat(count("select count(*) from pg_constraint where conrelid='transactions'::regclass and contype='u'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from pg_indexes where indexname in ('uq_users_username_lower',"
                + "'uq_users_email_lower','uq_tx_owner_idempotency')")).isZero();
    }

    @Test
    void upgradeKeepsAllDataSwapsTheKeyConstraintAndAddsTheNewIndexes() {
        run("schema.sql");

        assertThat(count("select count(*) from users")).isEqualTo(3);
        assertThat(count("select count(*) from wallets")).isEqualTo(3);
        assertThat(count("select count(*) from transactions")).isEqualTo(4);
        assertThat(count("select count(*) from transactions where idempotency_key in ('k1','k2','d1')")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(balance) from wallets", java.math.BigDecimal.class))
                .isEqualByComparingTo("300");

        assertThat(count("select count(*) from pg_constraint where conrelid='transactions'::regclass "
                + "and contype='u'")).as("old global unique constraint dropped").isZero();
        assertThat(count("select count(*) from pg_indexes where indexname in ('uq_users_username_lower',"
                + "'uq_users_email_lower','uq_tx_owner_idempotency')")).isEqualTo(3);

        // the stray whitespace of the legacy row was trimmed so lookups by the clean name find it
        assertThat(jdbc.queryForObject("select username from users where id=3", String.class)).isEqualTo("dave");
        assertThat(jdbc.queryForObject("select email from users where id=3", String.class))
                .isEqualTo("dave@example.com");
    }

    @Test
    void upgradedDatabaseEnforcesPerOwnerKeysAndCaseInsensitiveUsers() {
        run("schema.sql");

        // k1 belongs to wallet 1 (sender): wallet 1 cannot reuse it, wallet 2 can
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,"
                + "status,idempotency_key) values (1,2,1,'TRANSFER','COMPLETED','k1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (3,2,1,'TRANSFER','COMPLETED','k1')");
        // a deposit is owned by its receiver: the key d1 of wallet 1 is taken for wallet 1 only
        assertThatThrownBy(() -> jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,"
                + "idempotency_key) values (1,1,'DEPOSIT','COMPLETED','d1')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into transactions(receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (2,1,'DEPOSIT','COMPLETED','d1')");

        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('ALICE','other@example.com','h')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('zed','Alice@Example.com','h')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradeAddsTheDepositRequestsTableToAnOldDatabaseAndEnforcesItsConstraints() {
        assertThat(count("select count(*) from pg_tables where schemaname=current_schema() "
                + "and tablename='deposit_requests'")).as("old database has no such table").isZero();
        run("schema.sql");
        assertThat(count("select count(*) from pg_tables where schemaname=current_schema() "
                + "and tablename='deposit_requests'")).isEqualTo(1);
        assertThat(count("select count(*) from pg_indexes where indexname in ('idx_dr_wallet','idx_dr_status',"
                + "'uq_dr_wallet_idempotency','uq_dr_transaction')")).isEqualTo(4);
        assertThat(count("select count(*) from users")).as("old data untouched").isEqualTo(3);

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
        // APPROVED needs the decision data and a transaction; one transaction cannot back two requests
        assertThatThrownBy(() -> jdbc.update("update deposit_requests set status='APPROVED' where wallet_id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update deposit_requests set status='APPROVED', decided_at=now(), decided_by=3, transaction_id=1 "
                + "where wallet_id=1");
        assertThatThrownBy(() -> jdbc.update("update deposit_requests set status='APPROVED', decided_at=now(), "
                + "decided_by=3, transaction_id=1 where wallet_id=2")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradeIsIdempotentAndChangesNothingTheSecondTime() {
        run("schema.sql");
        long idx = count("select count(*) from pg_indexes where schemaname=current_schema()");
        long cons = count("select count(*) from pg_constraint");
        run("schema.sql");
        run("schema.sql");
        assertThat(count("select count(*) from pg_indexes where schemaname=current_schema()")).isEqualTo(idx);
        assertThat(count("select count(*) from pg_constraint")).isEqualTo(cons);
        assertThat(count("select count(*) from users")).isEqualTo(3);
        assertThat(count("select count(*) from transactions")).isEqualTo(4);
    }

    @Test
    void usernameClashesAbortTheUpgradeWithAClearMessageAndLoseNoData() {
        jdbc.update("insert into users(id,username,email,password_hash) values (10,'Alice','alice2@example.com','h')");

        assertThatThrownBy(() -> run("schema.sql"))
                .hasStackTraceContaining("Schema upgrade aborted")
                .hasStackTraceContaining("same username ignoring case");
        assertThat(count("select count(*) from users")).as("nothing was deleted or renamed").isEqualTo(4);
        assertThat(count("select count(*) from users where username in ('alice','Alice')")).isEqualTo(2);
        assertThat(count("select count(*) from pg_indexes where indexname='uq_users_username_lower'")).isZero();
    }

    @Test
    void emailClashesAbortTheUpgradeWithAClearMessageAndLoseNoData() {
        jdbc.update("insert into users(id,username,email,password_hash) values (10,'alice2','ALICE@example.com','h')");

        assertThatThrownBy(() -> run("schema.sql"))
                .hasStackTraceContaining("Schema upgrade aborted")
                .hasStackTraceContaining("same email ignoring case");
        assertThat(count("select count(*) from users")).isEqualTo(4);
        assertThat(count("select count(*) from pg_indexes where indexname='uq_users_email_lower'")).isZero();
    }
}
