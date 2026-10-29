package com.example.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A user's request to have money added to their wallet, stored in the {@code deposit_requests} table. Nothing is
 * credited until an admin approves it; approval creates a {@code DEPOSIT} {@link Transaction}, linked through
 * {@code transactionId}. The optional idempotency key is unique per wallet.
 */
@Entity
@Table(name = "deposit_requests")
public class DepositRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private Long walletId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 255, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DepositRequestStatus status = DepositRequestStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    /** JPA callback that sets {@code createdAt} to the current time before the first insert. */
    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS); }

    /** @return the generated database id, or {@code null} before the request is saved */
    public Long getId() { return id; }
    /** @return the id of the wallet to be credited */
    public Long getWalletId() { return walletId; }
    /** @param v the id of the wallet to be credited */
    public void setWalletId(Long v) { this.walletId = v; }
    /** @return the requesting user */
    public User getUser() { return user; }
    /** @param user the requesting user */
    public void setUser(User user) { this.user = user; }
    /** @return the requested amount */
    public BigDecimal getAmount() { return amount; }
    /** @param amount the requested amount (stored with the column scale of 4) */
    public void setAmount(BigDecimal amount) {
        this.amount = amount == null ? null : amount.setScale(4, RoundingMode.HALF_UP);
    }
    /** @return the note or bank transfer reference of the requester, or {@code null} */
    public String getNote() { return note; }
    /** @param note the note (at most 255 characters), or {@code null} */
    public void setNote(String note) { this.note = note; }
    /** @return the current status */
    public DepositRequestStatus getStatus() { return status; }
    /** @param status the new status */
    public void setStatus(DepositRequestStatus status) { this.status = status; }
    /** @return the creation timestamp, set automatically on first save */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @return when an admin decided the request, or {@code null} while pending */
    public LocalDateTime getDecidedAt() { return decidedAt; }
    /** @param v when the request was decided */
    public void setDecidedAt(LocalDateTime v) { this.decidedAt = v; }
    /** @return the id of the deciding admin, or {@code null} while pending */
    public Long getDecidedBy() { return decidedBy; }
    /** @param v the id of the deciding admin */
    public void setDecidedBy(Long v) { this.decidedBy = v; }
    /** @return the admin note, or {@code null} */
    public String getDecisionNote() { return decisionNote; }
    /** @param v the admin note (at most 255 characters), or {@code null} */
    public void setDecisionNote(String v) { this.decisionNote = v; }
    /** @return the id of the DEPOSIT transaction created on approval, or {@code null} */
    public Long getTransactionId() { return transactionId; }
    /** @param v the id of the DEPOSIT transaction created on approval */
    public void setTransactionId(Long v) { this.transactionId = v; }
    /** @return the client-supplied idempotency key, or {@code null} if none was given */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** @param k the idempotency key, or {@code null} for none */
    public void setIdempotencyKey(String k) { this.idempotencyKey = k; }
}
