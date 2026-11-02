package com.example.wallet.dto;

import com.example.wallet.entity.Wallet;
import java.math.BigDecimal;

/**
 * Wallet representation returned by the API.
 *
 * @param id       wallet id
 * @param userId   id of the owning user
 * @param username username of the owning user
 * @param balance  current balance
 * @param currency three-letter currency code
 */
public record WalletResponse(Long id, Long userId, String username, BigDecimal balance, String currency) {
    /**
     * Maps a wallet entity to its response form.
     *
     * @param w the wallet; its user must be loaded
     * @return the response DTO
     */
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getId(), w.getUser().getId(), w.getUser().getUsername(), w.getBalance(), w.getCurrency());
    }
}
