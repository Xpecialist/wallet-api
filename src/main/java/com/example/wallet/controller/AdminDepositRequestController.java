package com.example.wallet.controller;

import com.example.wallet.dto.AdminDepositRequestResponse;
import com.example.wallet.dto.DepositDecisionRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.entity.DepositRequestStatus;
import com.example.wallet.service.DepositRequestService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Endpoints under {@code /api/admin/deposit-requests}, restricted to the ADMIN role by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/admin/deposit-requests")
public class AdminDepositRequestController {
    private final DepositRequestService service;

    /** @param service the deposit request service */
    public AdminDepositRequestController(DepositRequestService service) { this.service = service; }

    /**
     * {@code GET /api/admin/deposit-requests}. Admin only. Lists all users' requests. Query parameters: {@code page},
     * {@code size} (default 20, at most 100), {@code sort} (id, createdAt, amount, status, decidedAt), and the filters
     * {@code status}, {@code userId}, {@code username}. Without {@code sort} and {@code status}, PENDING requests come
     * first.
     *
     * @param status   optional status filter
     * @param userId   optional requesting user id
     * @param username optional requesting username
     * @param pageable page number, size and sort
     * @return one page of requests (errors: 400 invalid parameter or sort property, 401, 403 not an admin)
     */
    @GetMapping
    public PageResponse<AdminDepositRequestResponse> list(
            @RequestParam(required = false) DepositRequestStatus status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String username,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.listAll(status, userId, username, pageable);
    }

    /**
     * {@code POST /api/admin/deposit-requests/{id}/approve}. Admin only. Credits the requester's wallet exactly once
     * and creates the linked DEPOSIT transaction, see {@link DepositRequestService#approve}. The body is optional.
     *
     * @param id   the request id
     * @param req  optional decision note
     * @param auth the authenticated admin
     * @return the approved request (errors: 400 note too long, 401, 403, 404 not found, 409 already decided or the
     *         user is disabled)
     */
    @PostMapping("/{id}/approve")
    public AdminDepositRequestResponse approve(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) DepositDecisionRequest req,
                                               Authentication auth) {
        return service.approve(auth.getName(), id, req == null ? null : req.decisionNote());
    }

    /**
     * {@code POST /api/admin/deposit-requests/{id}/reject}. Admin only. Marks the request REJECTED; no money moves.
     *
     * @param id   the request id
     * @param req  the decision note (required, at most 255 characters)
     * @param auth the authenticated admin
     * @return the rejected request (errors: 400 missing or too long note, 401, 403, 404 not found, 409 already
     *         decided)
     */
    @PostMapping("/{id}/reject")
    public AdminDepositRequestResponse reject(@PathVariable Long id,
                                              @Valid @RequestBody DepositDecisionRequest req, Authentication auth) {
        return service.reject(auth.getName(), id, req.decisionNote());
    }
}
