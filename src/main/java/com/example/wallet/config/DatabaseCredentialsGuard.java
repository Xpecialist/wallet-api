package com.example.wallet.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup with {@link MissingDatabaseCredentialsException} when the datasource user name or password resolves to
 * an empty value. Runs as a bean factory post-processor, before the DataSource (and so Flyway, which uses the same
 * credentials) is created, so the failure is the clear message and not a connection error.
 */
@Component
public class DatabaseCredentialsGuard implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> missing = new ArrayList<>();
        if (isBlank(env.getProperty("spring.datasource.username"))) missing.add("DB_USERNAME");
        if (isBlank(env.getProperty("spring.datasource.password"))) missing.add("DB_PASSWORD");
        if (!missing.isEmpty()) throw new MissingDatabaseCredentialsException(missing);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
