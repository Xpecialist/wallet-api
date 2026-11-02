package com.example.wallet.controller;

import com.example.wallet.dto.AmountRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.dto.UserStatusRequest;
import com.example.wallet.service.IdempotentRetry;
import com.example.wallet.service.TransactionService;
import com.example.wallet.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Endpoints under {@code /api/admin}, restricted to users with the ADMIN role by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final UserService users;
    private final TransactionService transactions;

    /**
     * Creates the controller.
     *
     * @param users        user service
     * @param transactions transaction service
     */
    public AdminController(UserService users, TransactionService transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    /**
     * {@code GET /api/admin/users}. Admin only. Lists users (without password hashes), sorted and paged. Query
     * parameters: {@code page} (zero-based), {@code size} (default 20, at most 100), {@code sort} (for example
     * {@code username,asc}; default {@code id}).
     *
     * @param pageable page number, size and sort
     * @return one page of users (errors: 400 for an invalid sort property, 401 if not authenticated,
     *         403 if not an admin)
     */
    @GetMapping("/users")
    public PageResponse<UserResponse> users(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return users.listAll(pageable);
    }

    /**
     * {@code PATCH /api/admin/users/{id}/status}. Admin only. Enables or disables a user. A disabled user cannot
     * log in and their existing tokens are rejected.
     *
     * @param id   the user id
     * @param req  the new status
     * @param auth the authenticated admin
     * @return the updated user (errors: 400 on validation failure or when disabling one's own account,
     *         401 if not authenticated, 403 if not an admin, 404 if the user is not found)
     */
    @PatchMapping("/users/{id}/status")
    public UserResponse setStatus(@PathVariable Long id, @Valid @RequestBody UserStatusRequest req,
                                  Authentication auth) {
        return users.setEnabled(id, req.enabled(), auth.getName());
    }

    /**
     * {@code GET /api/admin/transactions}. Admin only. Lists all transactions, newest first by default. Accepts the
     * same paging, sorting and filter parameters as {@code GET /api/transactions}.
     *
     * @param filter   the optional filters
     * @param pageable page number, size and sort
     * @return one page of transactions (errors: 400 for an invalid parameter or sort property,
     *         401 if not authenticated, 403 if not an admin)
     */
    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(
            TransactionFilter filter,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return transactions.listAll(filter, pageable);
    }

    /**
     * {@code POST /api/admin/wallets/{id}/deposit}. Admin only. Credits any wallet (simulated).
     *
     * @param id             the wallet id
     * @param req            the amount
     * @param idempotencyKey optional {@code Idempotency-Key} header; repeating a key returns the original result
     * @return 201 with the DEPOSIT transaction (errors: 400 invalid amount, 401 not authenticated,
     *         403 not an admin, 404 wallet not found, 409 idempotency key already used with a different request)
     */
    @PostMapping("/wallets/{id}/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable Long id,
            @Valid @RequestBody AmountRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(IdempotentRetry.run(() -> transactions.deposit(id, req.amount(), idempotencyKey)));
    }
}
