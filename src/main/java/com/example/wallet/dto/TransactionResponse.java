package com.example.wallet.dto;

import com.example.wallet.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction representation returned by the API. The idempotency key is deliberately not included: both parties of
 * a transfer can read the transaction, and the key belongs to the requester only.
 *
 * @param id               transaction id
 * @param senderWalletId   id of the debited wallet, or {@code null} for a deposit
 * @param receiverWalletId id of the credited wallet, or {@code null} for a withdrawal
 * @param amount           transferred amount
 * @param type             type name: {@code TRANSFER}, {@code DEPOSIT} or {@code WITHDRAWAL}
 * @param status           status name, e.g. {@code COMPLETED}
 * @param note             optional note, or {@code null}
 * @param createdAt        creation timestamp
 */
public record TransactionResponse(Long id, Long senderWalletId, Long receiverWalletId, BigDecimal amount,
                                  String type, String status, String note, LocalDateTime createdAt) {
    /**
     * Maps a transaction entity to its response form.
     *
     * @param t the transaction
     * @return the response DTO
     */
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getId(), t.getSenderWalletId(), t.getReceiverWalletId(), t.getAmount(),
                t.getType().name(), t.getStatus().name(), t.getNote(), t.getCreatedAt());
    }
}
