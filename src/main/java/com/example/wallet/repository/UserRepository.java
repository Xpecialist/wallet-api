package com.example.wallet.repository;

import com.example.wallet.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link User}. Username and email lookups ignore case. */
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * Finds a user by username, ignoring case (matches the unique index on {@code lower(username)}).
     *
     * @param username the username
     * @return the user, or empty if none exists
     */
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsername(@Param("username") String username);

    /**
     * Checks whether a username is taken, ignoring case.
     *
     * @param username the username
     * @return {@code true} if a user with this username exists
     */
    @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
    boolean existsByUsername(@Param("username") String username);

    /**
     * Checks whether an email is already registered, ignoring case.
     *
     * @param email the email address
     * @return {@code true} if a user with this email exists
     */
    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    /**
     * Revokes every token issued so far for the user with one atomic {@code UPDATE} (no read-modify-write, so
     * concurrent bumps are never lost). The row lock is held until the caller's transaction ends. Pending changes are
     * flushed first and the persistence context is cleared afterwards so no stale entity survives.
     *
     * @param id the user id
     * @return the number of rows changed (1, or 0 if the user does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set token_version = token_version + 1 where id = :id", nativeQuery = true)
    int bumpTokenVersion(@Param("id") Long id);

    /**
     * Changes the password and revokes every token (token version + 1) in ONE statement, but only while the caller's
     * token is still current and the account enabled: a token revoked or an account disabled after the request passed
     * authentication matches no row, so a revoked session cannot finish a password change and a disabled account is
     * never touched. Only these two columns are written, so a concurrent email change or disable is never overwritten.
     * Concurrent changes with the same token serialize on the row lock; the loser re-checks the version and matches
     * nothing.
     *
     * @param id           the user id
     * @param tokenVersion the version the caller's token carried (the stored version it was checked against)
     * @param hash         the BCrypt hash of the new password
     * @return 1 if changed, 0 if the token is no longer current, the account is disabled or the user does not exist
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set password_hash = :hash, token_version = token_version + 1 "
            + "where id = :id and token_version = :tv and enabled = true", nativeQuery = true)
    int changePasswordIfCurrent(@Param("id") Long id, @Param("tv") long tokenVersion, @Param("hash") String hash);

    /**
     * Changes only the email address (a targeted UPDATE, so nothing else of the row is written back from a stale copy).
     *
     * @param id    the user id
     * @param email the new, trimmed email
     * @return the number of rows changed (1, or 0 if the user does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set email = :email where id = :id", nativeQuery = true)
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * Re-enables the user (only the {@code enabled} column; the token version is not changed).
     *
     * @param id the user id
     * @return the number of rows changed (1, or 0 if the user does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set enabled = true where id = :id", nativeQuery = true)
    int enable(@Param("id") Long id);

    /**
     * Disables the user and revokes every token (token version + 1) in ONE statement, so there is no moment with a
     * disabled user whose old tokens still match, and a racing password or email change cannot undo it.
     *
     * @param id the user id
     * @return the number of rows changed (1, or 0 if the user does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set enabled = false, token_version = token_version + 1 where id = :id",
            nativeQuery = true)
    int disableAndRevoke(@Param("id") Long id);
}
