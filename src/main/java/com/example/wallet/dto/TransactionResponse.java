package com.example.wallet.dto;

import com.example.wallet.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction representation returned by the API.
 *
 * @param id               transaction id
 * @param senderWalletId   id of the debited wallet
 * @param receiverWalletId id of the credited wallet
 * @param amount           transferred amount
 * @param status           status name, e.g. {@code COMPLETED}
 * @param createdAt        creation timestamp
 * @param idempotencyKey   idempotency key used for the transfer, or {@code null}
 */
public record TransactionResponse(Long id, Long senderWalletId, Long receiverWalletId, BigDecimal amount,
                                  String status, LocalDateTime createdAt, String idempotencyKey) {
    /**
     * Maps a transaction entity to its response form.
     *
     * @param t the transaction
     * @return the response DTO
     */
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getId(), t.getSenderWalletId(), t.getReceiverWalletId(), t.getAmount(),
                t.getStatus().name(), t.getCreatedAt(), t.getIdempotencyKey());
    }
}
