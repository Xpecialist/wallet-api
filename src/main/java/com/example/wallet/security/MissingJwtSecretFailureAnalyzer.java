package com.example.wallet.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link MissingJwtSecretException} into a short "failed to start" report instead of a stack trace. */
public class MissingJwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<MissingJwtSecretException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingJwtSecretException cause) {
        return new FailureAnalysis(
                "No JWT signing secret is configured (app.jwt.secret / JWT_SECRET is empty). The application has no "
                        + "built-in default secret on purpose.",
                "Set the JWT_SECRET environment variable to a Base64 string that decodes to at least 32 bytes, or, "
                        + "for local development only, start with --spring.profiles.active=dev.",
                cause);
    }
}
