package com.example.wallet.repository;

import com.example.wallet.entity.DepositRequest;
import com.example.wallet.entity.DepositRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link DepositRequest}; supports filtering with {@code Specification}s. */
public interface DepositRequestRepository
        extends JpaRepository<DepositRequest, Long>, JpaSpecificationExecutor<DepositRequest> {
    /**
     * Loads a request and takes a pessimistic write lock ({@code SELECT ... FOR UPDATE}) on its row until the
     * surrounding transaction ends. A concurrent decision waits here and then sees the committed status. Must be
     * the first load of this request in the transaction, and be called inside one.
     *
     * @param id the request id
     * @return the locked request, or empty if it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DepositRequest d where d.id = :id")
    Optional<DepositRequest> findByIdForUpdate(@Param("id") Long id);

    /**
     * Finds the request a wallet created with the given idempotency key (keys of other wallets are invisible).
     *
     * @param walletId       the owning wallet
     * @param idempotencyKey the key
     * @return the request, or empty if this wallet has not used the key
     */
    Optional<DepositRequest> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);

    /**
     * Counts a wallet's requests in the given status.
     *
     * @param walletId the wallet
     * @param status   the status
     * @return the number of matching requests
     */
    long countByWalletIdAndStatus(Long walletId, DepositRequestStatus status);
}
