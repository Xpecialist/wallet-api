package com.example.wallet.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Hard safety guard for the integration tests. Runs before the Spring context (and so before any SQL) and aborts
 * unless the RESOLVED datasource URL (including env var / system property overrides) points at the dedicated
 * {@code walletdb_test} database on localhost. Then it drops the app tables so each run starts from an empty schema
 * (the app's own schema.sql recreates them at startup).
 */
public class TestDbGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final String TEST_DB = "walletdb_test";
    private static final Pattern ALLOWED =
            Pattern.compile("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/" + TEST_DB + "$");

    /** Throws unless the URL is exactly the test database (query string ignored). */
    public static void assertTestUrl(String url) {
        String bare = url == null ? "" : url.replaceFirst("\\?.*$", "");
        if (!ALLOWED.matcher(bare).matches()) {
            throw new IllegalStateException("REFUSING TO RUN: datasource URL '" + url
                    + "' is not the dedicated test database '" + TEST_DB + "' on localhost");
        }
    }

    /** Throws unless the live connection is really connected to the test database. */
    public static void assertConnectedToTestDb(Connection c) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select current_database()")) {
            rs.next();
            if (!TEST_DB.equals(rs.getString(1))) {
                throw new IllegalStateException("REFUSING TO RUN: connected to database '" + rs.getString(1) + "'");
            }
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        ConfigurableEnvironment env = ctx.getEnvironment();
        String url = env.getProperty("spring.datasource.url");
        assertTestUrl(url);
        try (Connection c = DriverManager.getConnection(url,
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"))) {
            assertConnectedToTestDb(c);
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS deposit_requests, transactions, wallets, users CASCADE");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reset test schema", e);
        }
    }
}
