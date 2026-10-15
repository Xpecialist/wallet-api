package com.example.wallet.service;

import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Money transfers between wallets and transaction queries. */
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
     *   <li>The sender is debited, the receiver credited, and a {@code COMPLETED} transaction is saved.
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
                if (!existing.get().getSenderWalletId().equals(senderRef.getId())) {
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
        t.setStatus(TransactionStatus.COMPLETED);
        t.setIdempotencyKey(idempotencyKey);
        return TransactionResponse.from(transactions.save(t));
    }

    /**
     * Lists all transactions where the user's wallet is sender or receiver, newest first.
     *
     * @param username the user whose history is requested
     * @return the transactions, possibly empty
     * @throws ApiException 404 if the user has no wallet
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> history(String username) {
        Wallet w = wallets.findByUserUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        return transactions.findBySenderWalletIdOrReceiverWalletIdOrderByCreatedAtDesc(w.getId(), w.getId())
                .stream().map(TransactionResponse::from).toList();
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
     * Lists every transaction in the system. Intended for the admin endpoint.
     *
     * @return all transactions
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> listAll() {
        return transactions.findAll().stream().map(TransactionResponse::from).toList();
    }
}
