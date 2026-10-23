package com.example.wallet.exception;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions thrown by controllers and services to JSON error responses of the form
 * {@code {"timestamp": ..., "status": ..., "error": ...}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * Handles failed logins, including a disabled account. Disabled is deliberately the same response as a wrong
     * password so a login cannot be used to find out which accounts exist or are disabled (Spring checks the enabled
     * flag before the password).
     *
     * @param e the exception
     * @return a 401 response with a generic message that does not reveal whether the username exists
     */
    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public ResponseEntity<Map<String, Object>> badCreds(AuthenticationException e) {
        return body(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    /**
     * Handles an unknown sort property in a paged request. The services whitelist sortable properties first, so
     * this is only a safety net; other {@code InvalidDataAccessApiUsageException}s are real server errors and are
     * not mapped here.
     *
     * @param e the exception
     * @return a 400 response
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Map<String, Object>> badSort(PropertyReferenceException e) {
        return body(HttpStatus.BAD_REQUEST, "Invalid sort parameter");
    }

    /**
     * Handles database constraint violations by SQLState. A unique violation (23505, e.g. a racing duplicate
     * registration or a simultaneous idempotent replay) is a 409. Out-of-range or too-long values (22003, 22001)
     * are a 400, and so is text the database cannot store (22021, a NUL character that got past request validation,
     * for example in a query parameter). Anything else (CHECK, foreign key, NOT NULL) indicates a bug, so it is logged
     * and returned as 500 instead of being disguised as a conflict.
     *
     * @param e the exception
     * @return a 409, 400 or 500 response with a generic message
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        String state = sqlState(e);
        if (state == null || state.equals("23505")) return body(HttpStatus.CONFLICT, "Data conflict");
        if (state.equals("22021")) return body(HttpStatus.BAD_REQUEST, "Input contains an invalid character");
        if (state.equals("22003") || state.equals("22001")) {
            return body(HttpStatus.BAD_REQUEST, "Value out of range or too long");
        }
        log.error("Unexpected database constraint violation (SQLState {})", state, e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
        }
        return null;
    }

    /**
     * Handles a concurrent-update clash detected by the wallet {@code @Version} check (normally prevented by the
     * row locks).
     *
     * @param e the exception
     * @return a 409 response telling the client to retry
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "Concurrent update, please retry");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status.value());
        m.put("error", message);
        return ResponseEntity.status(status).body(m);
    }
}
