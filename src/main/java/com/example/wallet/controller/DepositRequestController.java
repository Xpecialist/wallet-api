package com.example.wallet.controller;

import com.example.wallet.dto.CreateDepositRequest;
import com.example.wallet.dto.DepositRequestResponse;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.entity.DepositRequestStatus;
import com.example.wallet.service.DepositRequestService;
import com.example.wallet.service.IdempotentRetry;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated endpoints under {@code /api/deposit-requests}: a user asks for money to be added to their own wallet.
 * Nothing is credited until an admin approves (see {@link AdminDepositRequestController}).
 */
@RestController
@RequestMapping("/api/deposit-requests")
public class DepositRequestController {
    private final DepositRequestService service;

    /** @param service the deposit request service */
    public DepositRequestController(DepositRequestService service) { this.service = service; }

    /**
     * {@code POST /api/deposit-requests}. Any authenticated user. Creates a PENDING request for the caller's wallet.
     * See {@link DepositRequestService#create} for the rules.
     *
     * @param req            amount and optional note (for example a bank transfer reference)
     * @param idempotencyKey optional {@code Idempotency-Key} header; repeating a key returns the original request
     * @param auth           the authenticated caller
     * @return 201 with the request (errors: 400 invalid amount, note or key; 401 not authenticated; 404 no wallet;
     *         409 key already used with a different request, or too many pending requests)
     */
    @PostMapping
    public ResponseEntity<DepositRequestResponse> create(
            @Valid @RequestBody CreateDepositRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(IdempotentRetry.run(() -> service.create(auth.getName(), req, idempotencyKey)));
    }

    /**
     * {@code GET /api/deposit-requests}. Any authenticated user. Lists the caller's own requests, newest first by
     * default. Query parameters: {@code page} (zero-based), {@code size} (default 20, at most 100), {@code sort}
     * (for example {@code amount,asc}; allowed: id, createdAt, amount, status, decidedAt) and {@code status}.
     *
     * @param status   optional status filter
     * @param pageable page number, size and sort
     * @param auth     the authenticated caller
     * @return one page of requests (errors: 400 invalid parameter or sort property, 401, 404 if no wallet)
     */
    @GetMapping
    public PageResponse<DepositRequestResponse> list(
            @RequestParam(required = false) DepositRequestStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth) {
        return service.listOwn(auth.getName(), status, pageable);
    }

    /**
     * {@code GET /api/deposit-requests/{id}}. Any authenticated user; only the caller's own requests.
     *
     * @param id   the request id
     * @param auth the authenticated caller
     * @return the request (errors: 401, 404 if missing or not the caller's)
     */
    @GetMapping("/{id}")
    public DepositRequestResponse byId(@PathVariable Long id, Authentication auth) {
        return service.getOwn(auth.getName(), id);
    }
}
