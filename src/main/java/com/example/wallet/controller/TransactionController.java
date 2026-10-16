package com.example.wallet.controller;

import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
     * {@code GET /api/transactions}. Any authenticated user. Lists the caller's transactions, newest first by
     * default. Query parameters: {@code page} (zero-based), {@code size} (default 20, at most 100),
     * {@code sort} (for example {@code amount,asc}), and the filters {@code from}, {@code to} ({@code yyyy-MM-dd}),
     * {@code minAmount}, {@code maxAmount}, {@code status}, {@code type}.
     *
     * @param filter   the optional filters
     * @param pageable page number, size and sort
     * @param auth     the authenticated caller
     * @return one page of transactions (errors: 400 for an invalid parameter or sort property,
     *         401 if not authenticated, 404 if the caller has no wallet)
     */
    @GetMapping
    public PageResponse<TransactionResponse> history(
            TransactionFilter filter,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth) {
        return service.history(auth.getName(), filter, pageable);
    }

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
