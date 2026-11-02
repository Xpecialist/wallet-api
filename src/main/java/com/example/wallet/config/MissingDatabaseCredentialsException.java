package com.example.wallet.config;

import java.util.List;

/** Thrown at startup when the database user name or password is not configured. */
public class MissingDatabaseCredentialsException extends IllegalStateException {
    private final List<String> missing;

    /**
     * Creates the exception.
     *
     * @param missing the environment variables that are not set, for example {@code DB_PASSWORD}
     */
    public MissingDatabaseCredentialsException(List<String> missing) {
        super("Database credentials are not set: " + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    /** @return the names of the environment variables that are not set */
    public List<String> getMissing() { return missing; }
}
