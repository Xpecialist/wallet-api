package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Wallet}. The row-locking {@code findByIdForUpdate} comes from
 * {@link WalletRepositoryCustom}.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long>, WalletRepositoryCustom {
    /**
     * Finds the wallet owned by the user with the given username, ignoring case.
     *
     * @param username the owner's username
     * @return the wallet, or empty if the user or wallet does not exist
     */
    @Query("select w from Wallet w where lower(w.user.username) = lower(:username)")
    Optional<Wallet> findByUserUsername(@Param("username") String username);
}
