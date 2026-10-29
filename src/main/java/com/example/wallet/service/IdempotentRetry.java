package com.example.wallet.service;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Safety net for simultaneous requests with the same {@code Idempotency-Key}. The services already check the key
 * after taking the owner's wallet lock, so two such requests are serialised and the second one finds the first one's
 * row. If an insert still hits the unique idempotency index, the whole operation is run once more.
 * <p>
 * The retry must be called from outside the service's transaction (the controllers do): the failed attempt was rolled
 * back completely (balances included), so nothing it did is left over and the retry cannot move money twice; it runs
 * in a fresh transaction and a fresh Hibernate session, finds the committed row by (owner, key), compares the payload
 * and returns it, or fails with 409 on a mismatch.
 */
public final class IdempotentRetry {
    private IdempotentRetry() {
    }

    /**
     * Runs the operation; if it fails with a unique violation of an idempotency index, runs it exactly once more.
     * Any other failure, and a second failure, propagates unchanged.
     *
     * @param operation a call to a transactional service method
     * @param <T>       the result type
     * @return the result of the first successful attempt
     */
    public static <T> T run(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyKeyConflict(e)) throw e;
            return operation.get();
        }
    }

    /** @return true for SQLState 23505 on one of the idempotency indexes ({@code uq_*_idempotency}) */
    static boolean isIdempotencyKeyConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && "23505".equals(s.getSQLState())) {
                return s.getMessage() != null && s.getMessage().contains("_idempotency");
            }
        }
        return false;
    }
}
