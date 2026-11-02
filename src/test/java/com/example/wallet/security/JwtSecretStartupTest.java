package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** H1: the application must not start with a built-in JWT secret; the dev secret only lives in the dev profile. */
class JwtSecretStartupTest {
    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            p.load(in);
        }
        return p;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class)
            .withPropertyValues("app.jwt.expiration-ms=1000");

    @Test
    void contextFailsToStartWhenNoSecretIsConfigured() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(MissingJwtSecretException.class);
        });
    }

    @Test
    void contextFailsToStartWhenTheSecretIsEmpty() {
        runner.withPropertyValues("app.jwt.secret=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(MissingJwtSecretException.class);
        });
    }

    @Test
    void contextStartsWithAValidSecret() {
        runner.withPropertyValues("app.jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZi14eHh4")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void failureAnalyzerExplainsWhatToDo() {
        FailureAnalysis a = new MissingJwtSecretFailureAnalyzer().analyze(new MissingJwtSecretException());
        assertThat(a).isNotNull();
        assertThat(a.getDescription()).contains("JWT");
        assertThat(a.getAction()).contains("JWT_SECRET").contains("--spring.profiles.active=dev");
    }

    @Test
    void failureAnalyzerIsRegisteredInSpringFactories() {
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.loadFactoryNames(
                org.springframework.boot.diagnostics.FailureAnalyzer.class, getClass().getClassLoader()))
                .contains(MissingJwtSecretFailureAnalyzer.class.getName());
    }

    @Test
    void mainPropertiesHaveNoDefaultSecretAndTheDevProfileHoldsIt() throws Exception {
        Properties main = load("application.properties");
        String secret = main.getProperty("app.jwt.secret");
        assertThat(secret).isEqualTo("${JWT_SECRET:}"); // env var only, empty default
        Properties dev = load("application-dev.properties");
        assertThat(dev.getProperty("app.jwt.secret")).startsWith("${JWT_SECRET:").hasSizeGreaterThan(30);
        // the old committed default must not be reachable from the default profile
        assertThat(main.values()).noneMatch(v -> v.toString().contains("ZGV2LW9ubHktc2VjcmV0"));
    }

    @Test
    void newWalletsStartAtZeroByDefault() throws Exception {
        assertThat(load("application.properties").getProperty("app.wallet.initial-balance")).isEqualTo("0.00");
    }
}
