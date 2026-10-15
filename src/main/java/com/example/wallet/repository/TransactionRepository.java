package com.example.wallet.repository;

import com.example.wallet.entity.Transaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link Transaction}. */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    /**
     * Finds all transactions where the given wallet is sender or receiver, newest first.
     *
     * @param senderWalletId   wallet id to match as sender
     * @param receiverWalletId wallet id to match as receiver (pass the same id to get a wallet's full history)
     * @return matching transactions ordered by creation time, descending
     */
    List<Transaction> findBySenderWalletIdOrReceiverWalletIdOrderByCreatedAtDesc(Long senderWalletId, Long receiverWalletId);
    /**
     * Finds the transaction created with the given idempotency key.
     *
     * @param idempotencyKey the key
     * @return the transaction, or empty if the key has not been used
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
