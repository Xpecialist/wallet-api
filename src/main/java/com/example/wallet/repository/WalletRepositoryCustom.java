package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import java.util.Optional;

/** Custom {@link WalletRepository} operations that need the {@code EntityManager}. */
public interface WalletRepositoryCustom {
    /**
     * Loads a wallet by id and takes a pessimistic write lock ({@code SELECT ... FOR UPDATE}) on its row until
     * the surrounding transaction ends. The returned entity is refreshed after the lock is granted, so it holds the
     * committed balance and version even if the wallet was already loaded earlier in the same transaction. Must be
     * called inside a transaction.
     *
     * @param id the wallet id
     * @return the locked, up-to-date wallet, or empty if it does not exist
     */
    Optional<Wallet> findByIdForUpdate(Long id);
}
