package com.example.wallet.dto;

import com.example.wallet.entity.DepositRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Deposit request as returned to admins: the owner fields plus who requested and who decided. The idempotency key
 * is not included.
 *
 * @param id            request id
 * @param userId        id of the requesting user
 * @param username      username of the requesting user
 * @param walletId      wallet to be credited
 * @param amount        requested amount
 * @param note          the note of the requester, or {@code null}
 * @param status        {@code PENDING}, {@code APPROVED} or {@code REJECTED}
 * @param createdAt     creation timestamp
 * @param decidedAt     decision timestamp, or {@code null} while pending
 * @param decidedBy     id of the deciding admin, or {@code null} while pending
 * @param decisionNote  the admin note, or {@code null}
 * @param transactionId id of the DEPOSIT transaction created on approval, or {@code null}
 */
public record AdminDepositRequestResponse(Long id, Long userId, String username, Long walletId, BigDecimal amount,
                                          String note, String status, LocalDateTime createdAt,
                                          LocalDateTime decidedAt, Long decidedBy, String decisionNote,
                                          Long transactionId) {
    /**
     * Maps a deposit request entity to its admin response form.
     *
     * @param d the request
     * @return the response DTO
     */
    public static AdminDepositRequestResponse from(DepositRequest d) {
        return new AdminDepositRequestResponse(d.getId(), d.getUser().getId(), d.getUser().getUsername(),
                d.getWalletId(), d.getAmount(), d.getNote(), d.getStatus().name(), d.getCreatedAt(),
                d.getDecidedAt(), d.getDecidedBy(), d.getDecisionNote(), d.getTransactionId());
    }
}
