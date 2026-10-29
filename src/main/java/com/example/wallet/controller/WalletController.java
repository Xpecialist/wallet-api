package com.example.wallet.controller;

import com.example.wallet.dto.AmountRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.WalletResponse;
import com.example.wallet.service.IdempotentRetry;
import com.example.wallet.service.TransactionService;
import com.example.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Authenticated endpoints under {@code /api/wallets} for viewing wallets and withdrawing. There is no self-service deposit. */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    private final WalletService walletService;
    private final TransactionService transactionService;

    /**
     * Creates the controller.
     *
     * @param walletService      the wallet service
     * @param transactionService the transaction service, used for withdrawals
     */
    public WalletController(WalletService walletService, TransactionService transactionService) {
        this.walletService = walletService;
        this.transactionService = transactionService;
    }

    /**
     * {@code POST /api/wallets/me/withdraw}. Any authenticated user. Withdraws from the caller's own wallet
     * (simulated).
     *
     * @param req            the amount
     * @param idempotencyKey optional {@code Idempotency-Key} header; repeating a key returns the original result
     * @param auth           the authenticated caller
     * @return 201 with the WITHDRAWAL transaction (errors: 400 invalid amount, 401 not authenticated,
     *         404 no wallet, 409 idempotency key already used with a different request, 422 insufficient funds)
     */
    @PostMapping("/me/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @Valid @RequestBody AmountRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(IdempotentRetry.run(
                        () -> transactionService.withdraw(auth.getName(), req.amount(), idempotencyKey)));
    }

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
     * @return the wallet (errors: 401 if not authenticated, 404 if not found or not owner/admin)
     */
    @GetMapping("/{id}")
    public WalletResponse byId(@PathVariable Long id, Authentication auth) { return walletService.getById(id, auth); }
}
