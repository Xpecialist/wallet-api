package com.example.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A movement of funds, stored in the {@code transactions} table: a transfer between two wallets, a deposit into one
 * wallet, or a withdrawal from one wallet. Wallets are referenced by id only; the side outside the system is
 * {@code null}. The optional idempotency key is unique per owning wallet (sender, or receiver for a deposit).
 */
@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_wallet_id")
    private Long senderWalletId;

    @Column(name = "receiver_wallet_id")
    private Long receiverWalletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type = TransactionType.TRANSFER;

    @Column(length = 255)
    private String note;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /** JPA callback that sets {@code createdAt} to the current time before the first insert. */
    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    /** @return the generated database id, or {@code null} before the transaction is saved */
    public Long getId() { return id; }
    /** @return the id of the wallet that was debited, or {@code null} for a deposit */
    public Long getSenderWalletId() { return senderWalletId; }
    /** @param v the id of the wallet that is debited, or {@code null} for a deposit */
    public void setSenderWalletId(Long v) { this.senderWalletId = v; }
    /** @return the id of the wallet that was credited, or {@code null} for a withdrawal */
    public Long getReceiverWalletId() { return receiverWalletId; }
    /** @param v the id of the wallet that is credited, or {@code null} for a withdrawal */
    public void setReceiverWalletId(Long v) { this.receiverWalletId = v; }
    /** @return the transaction type */
    public TransactionType getType() { return type; }
    /** @param type the transaction type */
    public void setType(TransactionType type) { this.type = type; }
    /** @return the optional note, or {@code null} */
    public String getNote() { return note; }
    /** @param note the optional note (at most 255 characters), or {@code null} */
    public void setNote(String note) { this.note = note; }
    /** @return the transferred amount */
    public BigDecimal getAmount() { return amount; }
    /** @param amount the transferred amount */
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    /** @return the transaction status */
    public TransactionStatus getStatus() { return status; }
    /** @param status the transaction status */
    public void setStatus(TransactionStatus status) { this.status = status; }
    /** @return the creation timestamp, set automatically on first save */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @return the client-supplied idempotency key, or {@code null} if none was given */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** @param k the idempotency key, or {@code null} for none */
    public void setIdempotencyKey(String k) { this.idempotencyKey = k; }
}
