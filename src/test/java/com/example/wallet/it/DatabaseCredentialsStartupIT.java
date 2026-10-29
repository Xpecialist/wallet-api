package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.WalletApiApplication;
import com.example.wallet.config.MissingDatabaseCredentialsException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * L-2: boots the real application (no web server, no {@code it} profile) against the dedicated test database to check
 * the real startup rules for the database credentials: none outside the {@code dev} profile means no start. The URL is
 * forced to {@code walletdb_test}; the dev database is never contacted (a failing start never reaches the database).
 */
class DatabaseCredentialsStartupIT {
    private static final String TEST_DB = "--spring.datasource.url=jdbc:postgresql://localhost:5432/walletdb_test";
    private static final String JWT = "--app.jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZi14eHh4";
    // the local test database's credentials, as FlywayMigrationIT uses them (command-line args beat any env variable)
    private static final String USER = System.getenv().getOrDefault("DB_USERNAME", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");

    private static SpringApplication app(boolean guarded) {
        SpringApplication app = new SpringApplication(WalletApiApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        // TestDbGuard connects with the credentials, so it cannot run when they are the thing under test; the failing
        // starts stop before any connection, and the URL is still checked here.
        if (guarded) app.addInitializers(new TestDbGuard());
        else TestDbGuard.assertTestUrl(TEST_DB.substring(TEST_DB.indexOf("jdbc")));
        return app;
    }

    @Test
    void startupFailsFastWithoutAPassword() {
        // an empty value is what an unset DB_PASSWORD resolves to in application.properties
        assertThatThrownBy(() -> app(false).run(TEST_DB, JWT, "--DB_USERNAME=" + USER, "--DB_PASSWORD=",
                "--app.admin.password="))
                .isInstanceOf(MissingDatabaseCredentialsException.class)
                .hasMessageContaining("DB_PASSWORD").hasMessageNotContaining("DB_USERNAME");
    }

    @Test
    void startupFailsFastWithoutAUsername() {
        assertThatThrownBy(() -> app(false).run(TEST_DB, JWT, "--DB_USERNAME=", "--DB_PASSWORD=" + PASSWORD,
                "--app.admin.password="))
                .isInstanceOf(MissingDatabaseCredentialsException.class)
                .hasMessageContaining("DB_USERNAME");
    }

    @Test
    void startsWhenTheCredentialsAreProvided() {
        try (ConfigurableApplicationContext ctx = app(true).run(TEST_DB, JWT, "--DB_USERNAME=" + USER,
                "--DB_PASSWORD=" + PASSWORD, "--app.admin.password=")) {
            assertThat(ctx.isActive()).isTrue();
        }
    }

    @Test
    void devProfileStartsWithoutAnyCredentialVariables() {
        // dev supplies its own defaults, exactly as before. If the machine's DB_* env vars differ from the dev defaults,
        // those win (they override), so this test is meaningful only with the standard local setup.
        try (ConfigurableApplicationContext ctx = app(true).run(TEST_DB, "--spring.profiles.active=dev",
                "--app.admin.password=")) {
            assertThat(ctx.isActive()).isTrue();
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.username")).isNotBlank();
        }
    }
}
