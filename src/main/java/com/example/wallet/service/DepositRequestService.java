package com.example.wallet.service;

import com.example.wallet.dto.AdminDepositRequestResponse;
import com.example.wallet.dto.CreateDepositRequest;
import com.example.wallet.dto.DepositRequestResponse;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.entity.DepositRequest;
import com.example.wallet.entity.DepositRequestStatus;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import com.example.wallet.entity.User;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.DepositRequestRepository;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.repository.WalletRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit requests: a user asks for money to be added, and only an admin approval credits the wallet (for example
 * after the admin received a bank transfer).
 */
@Service
public class DepositRequestService {
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_NOTE_LENGTH = 255;
    private final DepositRequestRepository requests;
    private final TransactionRepository transactions;
    private final WalletRepository wallets;
    private final UserRepository users;
    private final int maxPending;

    /**
     * Creates the service.
     *
     * @param requests     deposit request repository
     * @param transactions transaction repository
     * @param wallets      wallet repository
     * @param users        user repository
     * @param maxPending   most PENDING requests one user may have at a time
     */
    public DepositRequestService(DepositRequestRepository requests, TransactionRepository transactions,
                                 WalletRepository wallets, UserRepository users,
                                 @Value("${app.deposit-requests.max-pending:5}") int maxPending) {
        this.requests = requests;
        this.transactions = transactions;
        this.wallets = wallets;
        this.users = users;
        this.maxPending = maxPending;
    }

    /**
     * Creates a PENDING request for the caller's own wallet; no money moves. The caller's wallet row is locked
     * ({@code SELECT ... FOR UPDATE}) first, which serialises the requests of one user so the pending cap and the
     * idempotency check cannot be raced. Idempotency keys are scoped to the wallet: the same key with the same amount
     * and note returns the original request (no new one), with a different amount or note it is a 409. A replay is
     * answered before the pending cap is checked.
     *
     * @param username       the authenticated caller
     * @param req            amount and optional note
     * @param idempotencyKey optional client-supplied key from the {@code Idempotency-Key} header, may be null
     * @return the created request, or the existing one for a repeated idempotency key
     * @throws ApiException 400 if the key is blank or over 100 characters; 404 if the caller has no wallet;
     *         409 if the key was already used for a different request, or the caller already has the maximum
     *         number of PENDING requests
     */
    @Transactional
    public DepositRequestResponse create(String username, CreateDepositRequest req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        Wallet ref = wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        Wallet wallet = wallets.findByIdForUpdate(ref.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        String note = normalizeNote(req.note());

        if (key != null) {
            var existing = requests.findByWalletIdAndIdempotencyKey(wallet.getId(), key);
            if (existing.isPresent()) {
                DepositRequest e = existing.get();
                if (e.getAmount().compareTo(req.amount()) != 0 || !Objects.equals(e.getNote(), note)) {
                    throw new ApiException(HttpStatus.CONFLICT, "Idempotency key already used with a different request");
                }
                return DepositRequestResponse.from(e);
            }
        }
        if (requests.countByWalletIdAndStatus(wallet.getId(), DepositRequestStatus.PENDING) >= maxPending) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Too many pending deposit requests (maximum " + maxPending + "), wait for a decision");
        }

        DepositRequest d = new DepositRequest();
        d.setWalletId(wallet.getId());
        d.setUser(wallet.getUser());
        d.setAmount(req.amount());
        d.setNote(note);
        d.setIdempotencyKey(key);
        return DepositRequestResponse.from(requests.save(d));
    }

    /**
     * Lists the caller's own requests, filtered, sorted and paged.
     *
     * @param username the authenticated caller
     * @param status   optional status filter
     * @param pageable page number, size and sort
     * @return one page of requests
     * @throws ApiException 400 if a sort property is not allowed; 404 if the caller has no wallet
     */
    @Transactional(readOnly = true)
    public PageResponse<DepositRequestResponse> listOwn(String username, DepositRequestStatus status,
                                                         Pageable pageable) {
        SortWhitelist.check(pageable, SortWhitelist.DEPOSIT_REQUESTS);
        Wallet w = wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        return PageResponse.from(requests.findAll(spec(w.getId(), status, null, null, false), pageable)
                .map(DepositRequestResponse::from));
    }

    /**
     * Returns one of the caller's own requests.
     *
     * @param username the authenticated caller
     * @param id       the request id
     * @return the request
     * @throws ApiException 404 if it does not exist or belongs to someone else (indistinguishable, so ids cannot be
     *         probed)
     */
    @Transactional(readOnly = true)
    public DepositRequestResponse getOwn(String username, Long id) {
        Wallet w = wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit request not found"));
        DepositRequest d = requests.findById(id).orElse(null);
        if (d == null || !d.getWalletId().equals(w.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Deposit request not found");
        }
        return DepositRequestResponse.from(d);
    }

    /**
     * Lists all users' requests for an admin. Without an explicit {@code sort} and without a status filter, PENDING
     * requests come first (newest first within each group).
     *
     * @param status   optional status filter
     * @param userId   optional requesting user id
     * @param username optional requesting username (exact, ignoring case)
     * @param pageable page number, size and sort
     * @return one page of requests
     * @throws ApiException 400 if a sort property is not allowed
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminDepositRequestResponse> listAll(DepositRequestStatus status, Long userId,
                                                              String username, Pageable pageable) {
        SortWhitelist.check(pageable, SortWhitelist.DEPOSIT_REQUESTS);
        boolean pendingFirst = status == null && pageable.getSort().isUnsorted();
        return PageResponse.from(requests.findAll(spec(null, status, userId, username, pendingFirst), pageable)
                .map(AdminDepositRequestResponse::from));
    }

    /**
     * Approves a request: credits the wallet exactly once and records the DEPOSIT transaction, in one database
     * transaction.
     * <ol>
     *   <li>The request row is locked ({@code SELECT ... FOR UPDATE}) and its status re-read under the lock. A
     *       concurrent approve or reject of the same request waits here, then sees the committed status and fails with
     *       409, so at most one decision ever succeeds.</li>
     *   <li>A request of a disabled user cannot be approved (409); it can still be rejected.</li>
     *   <li>The wallet row is locked with {@code findByIdForUpdate} (always after the request row; transfers lock
     *       wallets only, so no lock cycle exists), the balance is increased and a {@code COMPLETED}
     *       {@code DEPOSIT} transaction is saved, with a note naming the request.</li>
     *   <li>The request becomes APPROVED with the admin, time and the id of that transaction.</li>
     * </ol>
     *
     * @param adminUsername the authenticated admin
     * @param id            the request id
     * @param decisionNote  optional note (at most 255 characters)
     * @return the approved request
     * @throws ApiException 404 if the request or the admin is not found; 409 if the request is no longer PENDING or
     *         its user is disabled
     */
    @Transactional
    public AdminDepositRequestResponse approve(String adminUsername, Long id, String decisionNote) {
        User admin = findUser(adminUsername);
        DepositRequest d = lockPending(id);
        if (!d.getUser().isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "User is disabled; the request can only be rejected");
        }
        Wallet wallet = wallets.findByIdForUpdate(d.getWalletId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(d.getAmount()));

        Transaction t = new Transaction();
        t.setReceiverWalletId(wallet.getId());
        t.setAmount(d.getAmount());
        t.setType(TransactionType.DEPOSIT);
        t.setStatus(TransactionStatus.COMPLETED);
        String ref = "Deposit request #" + d.getId() + (d.getNote() == null ? "" : ": " + d.getNote());
        t.setNote(ref.length() > MAX_NOTE_LENGTH ? ref.substring(0, MAX_NOTE_LENGTH) : ref);
        t = transactions.save(t);

        decide(d, admin, DepositRequestStatus.APPROVED, normalizeNote(decisionNote));
        d.setTransactionId(t.getId());
        return AdminDepositRequestResponse.from(requests.save(d));
    }

    /**
     * Rejects a request; only its status changes, no money moves. The request row is locked and re-checked exactly as
     * in {@link #approve}.
     *
     * @param adminUsername the authenticated admin
     * @param id            the request id
     * @param decisionNote  the reason, required (non-blank, at most 255 characters)
     * @return the rejected request
     * @throws ApiException 400 if the note is blank; 404 if the request or the admin is not found; 409 if the request
     *         is no longer PENDING
     */
    @Transactional
    public AdminDepositRequestResponse reject(String adminUsername, Long id, String decisionNote) {
        String note = normalizeNote(decisionNote);
        if (note == null) throw new ApiException(HttpStatus.BAD_REQUEST, "decisionNote is required when rejecting");
        User admin = findUser(adminUsername);
        DepositRequest d = lockPending(id);
        decide(d, admin, DepositRequestStatus.REJECTED, note);
        return AdminDepositRequestResponse.from(requests.save(d));
    }

    /** Locks the request row and requires it to still be PENDING. */
    private DepositRequest lockPending(Long id) {
        DepositRequest d = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deposit request not found"));
        if (d.getStatus() != DepositRequestStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Deposit request already " + d.getStatus());
        }
        return d;
    }

    private static void decide(DepositRequest d, User admin, DepositRequestStatus status, String note) {
        d.setStatus(status);
        d.setDecidedBy(admin.getId());
        d.setDecidedAt(LocalDateTime.now());
        d.setDecisionNote(note);
    }

    private User findUser(String username) {
        return users.findByUsername(username).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /** Trims; blank becomes {@code null}. */
    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        return note.trim();
    }

    /**
     * Validates the {@code Idempotency-Key} header value. An absent header means "no key".
     *
     * @throws ApiException 400 if the key is blank or longer than 100 characters (the column size)
     */
    private static String normalizeKey(String key) {
        if (key == null) return null;
        if (key.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    /**
     * Builds the query conditions for a request list; each non-null argument adds one condition, combined with AND.
     *
     * @param walletId     only requests of this wallet
     * @param status       only this status
     * @param userId       only requests of this user id
     * @param username     only requests of this username, ignoring case
     * @param pendingFirst order PENDING before the rest, then newest first (only applied to the data query)
     */
    private Specification<DepositRequest> spec(Long walletId, DepositRequestStatus status, Long userId,
                                               String username, boolean pendingFirst) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (walletId != null) p.add(cb.equal(root.get("walletId"), walletId));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (userId != null) p.add(cb.equal(root.get("user").get("id"), userId));
            if (username != null && !username.isBlank()) {
                p.add(cb.equal(cb.lower(root.get("user").get("username")), username.trim().toLowerCase()));
            }
            if (pendingFirst && query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(
                        cb.asc(cb.<Integer>selectCase()
                                .when(cb.equal(root.get("status"), DepositRequestStatus.PENDING), 0).otherwise(1)),
                        cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
