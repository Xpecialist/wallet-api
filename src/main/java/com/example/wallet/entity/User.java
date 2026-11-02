package com.example.wallet.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A registered user, stored in the {@code users} table. Each user owns exactly one {@link Wallet}. The mutable columns
 * (email, password hash, enabled, token version) are never written by an entity update: each is changed only by its own
 * targeted UPDATE in {@code UserRepository}, so a stale copy of the row cannot overwrite a concurrent change.
 */
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** Never written by an entity update (see {@link #tokenVersion}); only {@code UserRepository.updateEmail} changes it. */
    @Column(nullable = false, unique = true, updatable = false)
    private String email;

    /** Never written by an entity update; only the targeted UPDATE in {@code UserRepository.changePasswordIfCurrent}. */
    @Column(name = "password_hash", nullable = false, updatable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    /** Never written by an entity update; only the targeted UPDATEs in {@code UserRepository} (disable also revokes tokens). */
    @Column(nullable = false, updatable = false)
    private boolean enabled = true;

    /**
     * Incremented to revoke every token issued so far. Never written by an entity update ({@code updatable = false}):
     * only the atomic {@code UserRepository.bumpTokenVersion} changes it, so a stale entity can never undo a revocation.
     */
    @Column(name = "token_version", nullable = false, updatable = false)
    private long tokenVersion;

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
    /** @return {@code true} if the user may log in and use the API; defaults to {@code true} */
    public boolean isEnabled() { return enabled; }
    /** @param enabled {@code false} to disable the user */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return the token version as loaded; a token is valid only while its claim equals the stored value */
    public long getTokenVersion() { return tokenVersion; }
    /** @return the creation timestamp, set automatically on first save */
    public LocalDateTime getCreatedAt() { return createdAt; }
}
