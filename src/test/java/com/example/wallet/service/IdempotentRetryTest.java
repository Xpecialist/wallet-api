package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.exception.ApiException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class IdempotentRetryTest {
    private static DataIntegrityViolationException violation(String sqlState, String message) {
        return new DataIntegrityViolationException("insert failed", new SQLException(message, sqlState));
    }

    private static DataIntegrityViolationException idempotencyViolation() {
        return violation("23505", "duplicate key value violates unique constraint \"uq_tx_owner_idempotency\"");
    }

    @Test
    void successRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        String r = IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void idempotencyIndexViolationIsRetriedOnceAndReturnsTheSecondResult() {
        AtomicInteger calls = new AtomicInteger();
        String r = IdempotentRetry.run(() -> {
            if (calls.incrementAndGet() == 1) throw idempotencyViolation();
            return "original";
        });
        assertThat(r).isEqualTo("original");
        assertThat(calls).hasValue(2);
    }

    @Test
    void depositRequestIndexViolationIsRetriedToo() {
        AtomicInteger calls = new AtomicInteger();
        IdempotentRetry.run(() -> {
            if (calls.incrementAndGet() == 1) {
                throw violation("23505", "duplicate key value violates unique constraint \"uq_dr_wallet_idempotency\"");
            }
            return "x";
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void aSecondViolationIsNotRetriedAgain() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            throw idempotencyViolation();
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void otherUniqueViolationsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            throw violation("23505", "duplicate key value violates unique constraint \"uq_users_username_lower\"");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void otherSqlStatesAndWrappedWithoutSqlExceptionAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            throw violation("23514", "check constraint uq_tx_owner_idempotency");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("no sql cause");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void apiExceptionsPassThroughWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> IdempotentRetry.run(() -> {
            calls.incrementAndGet();
            throw new ApiException(HttpStatus.CONFLICT, "Idempotency key already used with a different request");
        })).isInstanceOf(ApiException.class);
        assertThat(calls).hasValue(1);
    }
}
