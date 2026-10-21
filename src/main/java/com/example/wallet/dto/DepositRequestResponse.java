package com.example.wallet.dto;

import com.example.wallet.entity.DepositRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Deposit request as returned to its owner. The idempotency key and the deciding admin are deliberately not included.
 *
 * @param id            request id
 * @param walletId      wallet to be credited
 * @param amount        requested amount
 * @param note          the note of the requester, or {@code null}
 * @param status        {@code PENDING}, {@code APPROVED} or {@code REJECTED}
 * @param createdAt     creation timestamp
 * @param decidedAt     decision timestamp, or {@code null} while pending
 * @param decisionNote  the admin note, or {@code null}
 * @param transactionId id of the DEPOSIT transaction created on approval, or {@code null}
 */
public record DepositRequestResponse(Long id, Long walletId, BigDecimal amount, String note, String status,
                                     LocalDateTime createdAt, LocalDateTime decidedAt, String decisionNote,
                                     Long transactionId) {
    /**
     * Maps a deposit request entity to its response form.
     *
     * @param d the request
     * @return the response DTO
     */
    public static DepositRequestResponse from(DepositRequest d) {
        return new DepositRequestResponse(d.getId(), d.getWalletId(), d.getAmount(), d.getNote(),
                d.getStatus().name(), d.getCreatedAt(), d.getDecidedAt(), d.getDecisionNote(), d.getTransactionId());
    }
}
