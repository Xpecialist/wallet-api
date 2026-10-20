package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Wallet}. The row-locking {@code findByIdForUpdate} comes from
 * {@link WalletRepositoryCustom}.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long>, WalletRepositoryCustom {
    /**
     * Finds the wallet owned by the user with the given username.
     *
     * @param username the owner's username
     * @return the wallet, or empty if the user or wallet does not exist
     */
    Optional<Wallet> findByUserUsername(String username);
}
