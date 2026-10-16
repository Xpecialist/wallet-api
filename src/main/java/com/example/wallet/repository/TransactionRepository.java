package com.example.wallet.repository;

import com.example.wallet.entity.Transaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Spring Data JPA repository for {@link Transaction}; supports filtering with {@code Specification}s. */
public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
    /**
     * Finds the transaction created with the given idempotency key.
     *
     * @param idempotencyKey the key
     * @return the transaction, or empty if the key has not been used
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
