package com.example.wallet.service;

import com.example.wallet.dto.WalletResponse;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.WalletRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only wallet queries. */
@Service
public class WalletService {
    private final WalletRepository wallets;

    /** @param wallets wallet repository */
    public WalletService(WalletRepository wallets) { this.wallets = wallets; }

    /**
     * Returns the wallet owned by the given user.
     *
     * @param username the owner's username
     * @return the wallet
     * @throws ApiException 404 if the user has no wallet
     */
    @Transactional(readOnly = true)
    public WalletResponse getOwn(String username) {
        return WalletResponse.from(wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found")));
    }

    /**
     * Returns a wallet by id. Allowed for the wallet's owner and for admins.
     *
     * @param id   the wallet id
     * @param auth the authenticated caller
     * @return the wallet
     * @throws ApiException 404 if the wallet does not exist; 403 if the caller is neither owner nor admin
     */
    @Transactional(readOnly = true)
    public WalletResponse getById(Long id, Authentication auth) {
        Wallet w = wallets.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !w.getUser().getUsername().equals(auth.getName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed to view this wallet");
        }
        return WalletResponse.from(w);
    }
}
