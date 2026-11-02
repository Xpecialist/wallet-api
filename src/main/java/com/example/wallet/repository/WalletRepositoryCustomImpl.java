package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

/** Implementation of {@link WalletRepositoryCustom}; picked up by Spring Data through its {@code Impl} suffix. */
public class WalletRepositoryCustomImpl implements WalletRepositoryCustom {
    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Wallet> findByIdForUpdate(Long id) {
        Optional<Wallet> locked = em.createQuery("select w from Wallet w where w.id = :id", Wallet.class)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst();
        // The lock query returns an instance already managed in this transaction without reloading it, so its
        // balance/version may be stale (read before another transaction committed). Re-read it now that we hold the lock.
        locked.ifPresent(em::refresh);
        return locked;
    }
}
