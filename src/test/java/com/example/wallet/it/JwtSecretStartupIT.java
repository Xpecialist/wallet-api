package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.WalletApiApplication;
import com.example.wallet.security.MissingJwtSecretException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real application (no web server) against the dedicated test database, WITHOUT the {@code it} profile, to
 * check the real startup rules for the JWT secret: no secret means no start, the {@code dev} profile starts.
 * The datasource URL is forced to {@code walletdb_test}; the dev database is never contacted.
 */
class JwtSecretStartupIT {
    private static final String TEST_DB = "--spring.datasource.url=jdbc:postgresql://localhost:5432/walletdb_test";

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(WalletApiApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(new TestDbGuard());
        return app;
    }

    @Test
    void startupFailsFastWithoutAJwtSecret() {
        // an empty value is what an unset JWT_SECRET resolves to in application.properties
        assertThatThrownBy(() -> app().run(TEST_DB, "--app.jwt.secret=", "--app.admin.password="))
                .hasRootCauseInstanceOf(MissingJwtSecretException.class);
    }

    @Test
    void devProfileStartsWithItsOwnSecret() {
        try (ConfigurableApplicationContext ctx = app().run(TEST_DB, "--spring.profiles.active=dev",
                "--app.admin.password=")) {
            assertThat(ctx.isActive()).isTrue();
            assertThat(ctx.getEnvironment().getProperty("app.jwt.secret")).isNotBlank();
            assertThat(ctx.getEnvironment().getProperty("app.wallet.initial-balance")).isEqualTo("0.00");
        }
    }
}
