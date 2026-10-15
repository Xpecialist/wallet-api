package com.example.wallet.controller;

import com.example.wallet.dto.WalletResponse;
import com.example.wallet.service.WalletService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Authenticated endpoints under {@code /api/wallets} for viewing wallets. */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    private final WalletService walletService;

    /** @param walletService the wallet service */
    public WalletController(WalletService walletService) { this.walletService = walletService; }

    /**
     * {@code GET /api/wallets/me}. Any authenticated user. Returns the caller's own wallet.
     *
     * @param auth the authenticated caller
     * @return the wallet (errors: 401 if not authenticated, 404 if the caller has no wallet)
     */
    @GetMapping("/me")
    public WalletResponse me(Authentication auth) { return walletService.getOwn(auth.getName()); }

    /**
     * {@code GET /api/wallets/{id}}. Authenticated; allowed for the wallet's owner or an admin.
     *
     * @param id   the wallet id
     * @param auth the authenticated caller
     * @return the wallet (errors: 401 if not authenticated, 403 if not owner or admin, 404 if not found)
     */
    @GetMapping("/{id}")
    public WalletResponse byId(@PathVariable Long id, Authentication auth) { return walletService.getById(id, auth); }
}
