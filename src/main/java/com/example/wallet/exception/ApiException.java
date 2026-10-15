package com.example.wallet.exception;

import org.springframework.http.HttpStatus;

/**
 * Unchecked exception carrying the HTTP status to return. Converted into a JSON error body by
 * {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    /**
     * Creates an exception for the given HTTP status.
     *
     * @param status  the HTTP status to respond with
     * @param message the message returned to the client in the {@code error} field
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** @return the HTTP status to respond with */
    public HttpStatus getStatus() { return status; }
}
