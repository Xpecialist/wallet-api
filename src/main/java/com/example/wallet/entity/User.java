package com.example.wallet.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A registered user, stored in the {@code users} table. Each user owns exactly one {@link Wallet}. */
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA callback that sets {@code createdAt} to the current time before the first insert. */
    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    /** @return the generated database id, or {@code null} before the user is saved */
    public Long getId() { return id; }
    /** @return the unique login name */
    public String getUsername() { return username; }
    /** @param username the unique login name */
    public void setUsername(String username) { this.username = username; }
    /** @return the unique email address */
    public String getEmail() { return email; }
    /** @param email the unique email address */
    public void setEmail(String email) { this.email = email; }
    /** @return the BCrypt hash of the password */
    public String getPasswordHash() { return passwordHash; }
    /** @param passwordHash the BCrypt hash of the password (never the raw password) */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /** @return the user's role; defaults to {@link Role#USER} */
    public Role getRole() { return role; }
    /** @param role the user's role */
    public void setRole(Role role) { this.role = role; }
    /** @return the creation timestamp, set automatically on first save */
    public LocalDateTime getCreatedAt() { return createdAt; }
}
