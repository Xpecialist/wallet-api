package com.example.wallet.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.SpringFactoriesLoader;

/** L-2: no built-in database user or password; the app refuses to start without them, the dev profile supplies its own. */
class DatabaseCredentialsGuardTest {
    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            p.load(in);
        }
        return p;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseCredentialsGuard.class);

    @Test
    void contextFailsToStartWithoutCredentials() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).isInstanceOf(MissingDatabaseCredentialsException.class);
        });
    }

    @Test
    void contextFailsToStartWithAnEmptyOrBlankPassword() {
        for (String empty : new String[] {"", "   "}) {
            runner.withPropertyValues("spring.datasource.username=app", "spring.datasource.password=" + empty).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasMessageContaining("DB_PASSWORD")
                        .hasMessageNotContaining("DB_USERNAME");
            });
        }
    }

    @Test
    void contextFailsToStartWithoutAUsername() {
        runner.withPropertyValues("spring.datasource.username=", "spring.datasource.password=x").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("DB_USERNAME");
        });
    }

    @Test
    void contextStartsWithBothCredentials() {
        runner.withPropertyValues("spring.datasource.username=app", "spring.datasource.password=anything")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void failureAnalyzerExplainsWhatToDoAndNeverEchoesAValue() {
        FailureAnalysis a = new MissingDatabaseCredentialsFailureAnalyzer()
                .analyze(new MissingDatabaseCredentialsException(java.util.List.of("DB_PASSWORD")));
        assertThat(a).isNotNull();
        assertThat(a.getDescription()).contains("database credentials").contains("DB_PASSWORD");
        assertThat(a.getAction()).contains("DB_USERNAME").contains("DB_PASSWORD").contains("--spring.profiles.active=dev");
    }

    @Test
    void failureAnalyzerIsRegisteredInSpringFactories() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(org.springframework.boot.diagnostics.FailureAnalyzer.class,
                getClass().getClassLoader())).contains(MissingDatabaseCredentialsFailureAnalyzer.class.getName())
                .contains("com.example.wallet.security.MissingJwtSecretFailureAnalyzer");
    }

    @Test
    void mainPropertiesHaveNoDefaultCredentialsAndTheDevProfileHoldsThem() throws Exception {
        Properties main = load("application.properties");
        assertThat(main.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD:}");
        assertThat(main.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME:}");
        assertThat(main.getProperty("spring.datasource.url")).startsWith("${DB_URL:jdbc:postgresql://");
        assertThat(main.stringPropertyNames()).noneMatch(k -> k.startsWith("spring.flyway.") && k.matches(".*[.](user|password|url)"));
        Properties dev = load("application-dev.properties");
        assertThat(dev.getProperty("spring.datasource.password")).startsWith("${DB_PASSWORD:").doesNotEndWith(":}");
        assertThat(dev.getProperty("spring.datasource.username")).startsWith("${DB_USERNAME:").doesNotEndWith(":}");
    }
}
