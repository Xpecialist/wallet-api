package com.example.wallet.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link MissingDatabaseCredentialsException} into a short "failed to start" report instead of a stack trace. */
public class MissingDatabaseCredentialsFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingDatabaseCredentialsException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingDatabaseCredentialsException cause) {
        return new FailureAnalysis(
                "No database credentials are configured (not set: " + String.join(", ", cause.getMissing()) + "). "
                        + "The application has no built-in default database user or password on purpose.",
                "Set the DB_USERNAME and DB_PASSWORD environment variables to the credentials of a database user "
                        + "(ideally one with only the rights this application needs), or, for local development "
                        + "only, start with --spring.profiles.active=dev.",
                cause);
    }
}
