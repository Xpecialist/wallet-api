package com.example.wallet.security;

/** Thrown at startup when {@code app.jwt.secret} is not configured. */
public class MissingJwtSecretException extends IllegalStateException {
    /** Creates the exception with the standard message. */
    public MissingJwtSecretException() {
        super("app.jwt.secret is not set");
    }
}
