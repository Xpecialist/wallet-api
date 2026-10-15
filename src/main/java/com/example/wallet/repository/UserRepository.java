package com.example.wallet.repository;

import com.example.wallet.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link User}. */
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * Finds a user by exact username.
     *
     * @param username the username
     * @return the user, or empty if none exists
     */
    Optional<User> findByUsername(String username);

    /**
     * Checks whether a username is taken.
     *
     * @param username the username
     * @return {@code true} if a user with this username exists
     */
    boolean existsByUsername(String username);

    /**
     * Checks whether an email is already registered.
     *
     * @param email the email address
     * @return {@code true} if a user with this email exists
     */
    boolean existsByEmail(String email);
}
