package com.example.wallet.repository;

import com.example.wallet.entity.Transaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link Transaction}; supports filtering with {@code Specification}s. */
public interface TransactionRepository
        extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {
    /**
     * Finds the transaction an owner created with the given idempotency key. The owner is the sender wallet of a
     * transfer or withdrawal and the receiver wallet of a deposit, matching the unique index
     * {@code uq_tx_owner_idempotency}; keys of other owners are invisible.
     *
     * @param walletId       the owning wallet
     * @param idempotencyKey the key
     * @return the transaction, or empty if this wallet has not used the key
     */
    @Query("select t from Transaction t where t.idempotencyKey = :key "
            + "and coalesce(t.senderWalletId, t.receiverWalletId) = :walletId")
    Optional<Transaction> findByOwnerAndKey(@Param("walletId") Long walletId, @Param("key") String idempotencyKey);
}
