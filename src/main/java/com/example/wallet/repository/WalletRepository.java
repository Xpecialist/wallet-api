package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link Wallet}. */
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    /**
     * Finds the wallet owned by the user with the given username.
     *
     * @param username the owner's username
     * @return the wallet, or empty if the user or wallet does not exist
     */
    Optional<Wallet> findByUserUsername(String username);

    /**
     * Loads a wallet by id and takes a pessimistic write lock ({@code SELECT ... FOR UPDATE}) on its row until
     * the surrounding transaction ends. Must be called inside a transaction.
     *
     * @param id the wallet id
     * @return the locked wallet, or empty if it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
