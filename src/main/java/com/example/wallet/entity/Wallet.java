package com.example.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * A user's wallet, stored in the {@code wallets} table. Holds the balance and currency; the {@code version}
 * column provides optimistic locking on updates.
 */
@Entity
@Table(name = "wallets")
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency;

    @Version
    private Long version;

    /** @return the generated database id, or {@code null} before the wallet is saved */
    public Long getId() { return id; }
    /** @return the owning user (loaded eagerly) */
    public User getUser() { return user; }
    /** @param user the owning user */
    public void setUser(User user) { this.user = user; }
    /** @return the current balance */
    public BigDecimal getBalance() { return balance; }
    /** @param balance the new balance */
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    /** @return the three-letter currency code */
    public String getCurrency() { return currency; }
    /** @param currency the three-letter currency code */
    public void setCurrency(String currency) { this.currency = currency; }
    /** @return the optimistic-locking version, managed by JPA */
    public Long getVersion() { return version; }
}
