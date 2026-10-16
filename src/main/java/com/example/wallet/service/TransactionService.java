package com.example.wallet.service;

import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.WalletRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Money transfers between wallets, deposits and withdrawals, and transaction queries. */
@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final WalletRepository wallets;

    /**
     * Creates the service.
     *
     * @param transactions transaction repository
     * @param wallets      wallet repository
     */
    public TransactionService(TransactionRepository transactions, WalletRepository wallets) {
        this.transactions = transactions;
        this.wallets = wallets;
    }

    /**
     * Transfers funds from the caller's wallet to another wallet, all in one database transaction.
     * <ol>
     *   <li>Idempotency: if a non-blank key is given and a transaction with that key already exists, that
     *       transaction is returned unchanged when it was made by the same sender wallet (no new transfer); a
     *       key belonging to another sender yields 409. A blank key is treated as no key.</li>
     *   <li>The receiver is resolved from {@code receiverWalletId} if present, otherwise from
     *       {@code receiverUsername}. Transferring to oneself is rejected.</li>
     *   <li>Locking: both wallet rows are locked with {@code SELECT ... FOR UPDATE}, always in ascending id
     *       order so that two opposite transfers between the same wallets cannot deadlock.</li>
     *   <li>Checks, in order: same currency, then sufficient balance ({@code balance >= amount}).</li>
     *   <li>The sender is debited, the receiver credited, and a {@code COMPLETED} {@code TRANSFER} transaction
     *       is saved, including the optional note.
     *       Wallets also carry a {@code @Version} column, so the optimistic version is incremented on each
     *       update as a second safeguard.</li>
     * </ol>
     * Any exception rolls the whole transaction back, leaving balances unchanged.
     *
     * @param senderUsername the authenticated caller, whose wallet is debited
     * @param req            the transfer details
     * @param idempotencyKey optional client-supplied key from the {@code Idempotency-Key} header, may be null
     * @return the created transaction, or the existing one for a repeated idempotency key
     * @throws ApiException 400 if no receiver is given, the receiver is the sender, or currencies differ;
     *         404 if a wallet or the receiver username is not found; 409 if the idempotency key belongs to
     *         another sender; 422 if funds are insufficient
     */
    @Transactional
    public TransactionResponse transfer(String senderUsername, TransferRequest req, String idempotencyKey) {
        Wallet senderRef = wallets.findByUserUsername(senderUsername)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sender wallet not found"));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactions.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!senderRef.getId().equals(existing.get().getSenderWalletId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Idempotency key already used");
                }
                return TransactionResponse.from(existing.get());
            }
        } else {
            idempotencyKey = null;
        }

        Long receiverId;
        if (req.receiverWalletId() != null) {
            receiverId = req.receiverWalletId();
        } else if (req.receiverUsername() != null && !req.receiverUsername().isBlank()) {
            receiverId = wallets.findByUserUsername(req.receiverUsername())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Receiver not found")).getId();
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Provide receiverUsername or receiverWalletId");
        }
        if (receiverId.equals(senderRef.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot transfer to yourself");
        }

        // Lock both wallets in id order to avoid deadlocks.
        Long firstId = Math.min(senderRef.getId(), receiverId);
        Long secondId = Math.max(senderRef.getId(), receiverId);
        Wallet first = wallets.findByIdForUpdate(firstId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        Wallet second = wallets.findByIdForUpdate(secondId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Receiver wallet not found"));
        Wallet sender = first.getId().equals(senderRef.getId()) ? first : second;
        Wallet receiver = sender == first ? second : first;

        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Currency mismatch");
        }
        BigDecimal amount = req.amount();
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        Transaction t = new Transaction();
        t.setSenderWalletId(sender.getId());
        t.setReceiverWalletId(receiver.getId());
        t.setAmount(amount);
        t.setType(TransactionType.TRANSFER);
        t.setNote(req.note());
        t.setStatus(TransactionStatus.COMPLETED);
        t.setIdempotencyKey(idempotencyKey);
        return TransactionResponse.from(transactions.save(t));
    }

    /**
     * Credits a wallet with money from outside the system (simulated; no payment provider is involved) and records a
     * {@code DEPOSIT} transaction, in one database transaction. The wallet row is locked with
     * {@code SELECT ... FOR UPDATE} while the balance is updated. If a non-blank idempotency key is given and a
     * deposit to the same wallet already exists with that key, that transaction is returned and nothing is credited.
     *
     * @param walletId       the wallet to credit
     * @param amount         the amount to credit
     * @param idempotencyKey optional client-supplied key, may be null
     * @return the created transaction, or the existing one for a repeated idempotency key
     * @throws ApiException 404 if the wallet is not found; 409 if the key was used for a different operation
     */
    @Transactional
    public TransactionResponse deposit(Long walletId, BigDecimal amount, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            var existing = transactions.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                Transaction e = existing.get();
                if (e.getType() != TransactionType.DEPOSIT || !walletId.equals(e.getReceiverWalletId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Idempotency key already used");
                }
                return TransactionResponse.from(e);
            }
        }
        Wallet wallet = wallets.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        wallet.setBalance(wallet.getBalance().add(amount));

        Transaction t = new Transaction();
        t.setReceiverWalletId(wallet.getId());
        t.setAmount(amount);
        t.setType(TransactionType.DEPOSIT);
        t.setStatus(TransactionStatus.COMPLETED);
        t.setIdempotencyKey(key);
        return TransactionResponse.from(transactions.save(t));
    }

    /**
     * Deposits into the wallet owned by the given user.
     *
     * @param username       the wallet owner
     * @param amount         the amount to credit
     * @param idempotencyKey optional client-supplied key, may be null
     * @return the created (or existing) transaction
     * @throws ApiException 404 if the user has no wallet; otherwise as for {@link #deposit}
     */
    @Transactional
    public TransactionResponse depositToOwn(String username, BigDecimal amount, String idempotencyKey) {
        return deposit(ownWalletId(username), amount, idempotencyKey);
    }

    /**
     * Withdraws money from the wallet owned by the given user to outside the system (simulated) and records a
     * {@code WITHDRAWAL} transaction, in one database transaction. The wallet row is locked with
     * {@code SELECT ... FOR UPDATE}; the balance must be at least the amount. If a non-blank idempotency key is given
     * and a withdrawal from the same wallet already exists with that key, that transaction is returned and nothing
     * is debited.
     *
     * @param username       the wallet owner
     * @param amount         the amount to debit
     * @param idempotencyKey optional client-supplied key, may be null
     * @return the created transaction, or the existing one for a repeated idempotency key
     * @throws ApiException 404 if the user has no wallet; 409 if the key was used for a different operation;
     *         422 if funds are insufficient
     */
    @Transactional
    public TransactionResponse withdraw(String username, BigDecimal amount, String idempotencyKey) {
        Long walletId = ownWalletId(username);
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            var existing = transactions.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                Transaction e = existing.get();
                if (e.getType() != TransactionType.WITHDRAWAL || !walletId.equals(e.getSenderWalletId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Idempotency key already used");
                }
                return TransactionResponse.from(e);
            }
        }
        Wallet wallet = wallets.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));

        Transaction t = new Transaction();
        t.setSenderWalletId(wallet.getId());
        t.setAmount(amount);
        t.setType(TransactionType.WITHDRAWAL);
        t.setStatus(TransactionStatus.COMPLETED);
        t.setIdempotencyKey(key);
        return TransactionResponse.from(transactions.save(t));
    }

    /**
     * Finds the id of the wallet owned by a user.
     *
     * @param username the owner's username
     * @return the wallet id
     * @throws ApiException 404 if the user has no wallet
     */
    private Long ownWalletId(String username) {
        return wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found")).getId();
    }

    /**
     * Treats a null or blank idempotency key as "no key".
     *
     * @param key the key from the request header, may be null
     * @return the key, or {@code null} if it is null or blank
     */
    private String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * Lists transactions where the user's wallet is sender or receiver, filtered, sorted and paged.
     *
     * @param username the user whose history is requested
     * @param filter   optional filters
     * @param pageable page number, size and sort
     * @return one page of transactions
     * @throws ApiException 404 if the user has no wallet
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> history(String username, TransactionFilter filter, Pageable pageable) {
        Wallet w = wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        return PageResponse.from(transactions.findAll(spec(filter, w.getId()), pageable)
                .map(TransactionResponse::from));
    }

    /**
     * Returns a transaction by id. Allowed for admins and for the owners of the sender or receiver wallet.
     *
     * @param id   the transaction id
     * @param auth the authenticated caller
     * @return the transaction
     * @throws ApiException 404 if the transaction does not exist (or a non-admin caller has no wallet); 403 if
     *         the caller is not a participant and not an admin
     */
    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id, Authentication auth) {
        Transaction t = transactions.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin) {
            Wallet w = wallets.findByUserUsername(auth.getName())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
            if (!w.getId().equals(t.getSenderWalletId()) && !w.getId().equals(t.getReceiverWalletId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed to view this transaction");
            }
        }
        return TransactionResponse.from(t);
    }

    /**
     * Lists every transaction in the system, filtered, sorted and paged. Intended for the admin endpoint.
     *
     * @param filter   optional filters
     * @param pageable page number, size and sort
     * @return one page of transactions
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> listAll(TransactionFilter filter, Pageable pageable) {
        return PageResponse.from(transactions.findAll(spec(filter, null), pageable).map(TransactionResponse::from));
    }

    /**
     * Builds the query conditions for a transaction list. Each non-null filter field adds one condition, and all
     * conditions are combined with AND. {@code from} and {@code to} are compared with the creation time as whole
     * days: from the start of {@code from} up to the end of {@code to}.
     *
     * @param f        the filters
     * @param walletId if not null, only transactions where this wallet is sender or receiver
     * @return the combined specification
     */
    private Specification<Transaction> spec(TransactionFilter f, Long walletId) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (walletId != null) {
                p.add(cb.or(cb.equal(root.get("senderWalletId"), walletId),
                        cb.equal(root.get("receiverWalletId"), walletId)));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), f.from().atStartOfDay()));
            }
            if (f.to() != null) {
                p.add(cb.lessThan(root.<LocalDateTime>get("createdAt"), f.to().plusDays(1).atStartOfDay()));
            }
            if (f.minAmount() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("amount"), f.minAmount()));
            }
            if (f.maxAmount() != null) {
                p.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("amount"), f.maxAmount()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("type"), f.type()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
