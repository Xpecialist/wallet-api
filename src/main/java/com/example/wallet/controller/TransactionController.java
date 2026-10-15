package com.example.wallet.controller;

import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.service.TransactionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Authenticated endpoints under {@code /api/transactions} for transfers and transaction history. */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService service;

    /** @param service the transaction service */
    public TransactionController(TransactionService service) { this.service = service; }

    /**
     * {@code POST /api/transactions/transfer}. Any authenticated user. Transfers funds from the caller's wallet.
     * See {@link TransactionService#transfer} for the processing rules.
     *
     * @param req            the transfer details
     * @param idempotencyKey optional {@code Idempotency-Key} header; repeating a key returns the original result
     * @param auth           the authenticated caller
     * @return 201 with the transaction (errors: 400 invalid request, receiver is self, or currency mismatch;
     *         401 not authenticated; 404 receiver or wallet not found; 409 idempotency key used by another
     *         sender; 422 insufficient funds)
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.transfer(auth.getName(), req, idempotencyKey));
    }

    /**
     * {@code GET /api/transactions}. Any authenticated user. Lists the caller's transactions, newest first.
     *
     * @param auth the authenticated caller
     * @return the transactions (errors: 401 if not authenticated, 404 if the caller has no wallet)
     */
    @GetMapping
    public List<TransactionResponse> history(Authentication auth) { return service.history(auth.getName()); }

    /**
     * {@code GET /api/transactions/{id}}. Authenticated; allowed for the sender, the receiver, or an admin.
     *
     * @param id   the transaction id
     * @param auth the authenticated caller
     * @return the transaction (errors: 401 if not authenticated, 403 if not a participant or admin,
     *         404 if not found)
     */
    @GetMapping("/{id}")
    public TransactionResponse byId(@PathVariable Long id, Authentication auth) { return service.getById(id, auth); }
}
