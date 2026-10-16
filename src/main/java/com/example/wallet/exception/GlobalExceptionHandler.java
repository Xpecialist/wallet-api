package com.example.wallet.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions thrown by controllers and services to JSON error responses of the form
 * {@code {"timestamp": ..., "status": ..., "error": ...}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles {@link ApiException}.
     *
     * @param e the exception
     * @return a response with the exception's own status and message
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.getStatus(), e.getMessage());
    }

    /**
     * Handles request-body validation failures.
     *
     * @param e the validation exception
     * @return a 400 response listing each failing field as {@code field: message}, separated by semicolons
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * Handles failed logins.
     *
     * @param e the exception
     * @return a 401 response with a generic message that does not reveal whether the username exists
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> badCreds(BadCredentialsException e) {
        return body(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    /**
     * Handles login attempts by a disabled user.
     *
     * @param e the exception
     * @return a 403 response
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(DisabledException e) {
        return body(HttpStatus.FORBIDDEN, "Account is disabled");
    }

    /**
     * Handles an unknown sort property in a paged request.
     *
     * @param e the exception
     * @return a 400 response
     */
    @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
    public ResponseEntity<Map<String, Object>> badSort(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "Invalid sort parameter");
    }

    /**
     * Handles database constraint violations.
     *
     * @param e the exception
     * @return a 409 response with a generic message
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "Data conflict");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status.value());
        m.put("error", message);
        return ResponseEntity.status(status).body(m);
    }
}
