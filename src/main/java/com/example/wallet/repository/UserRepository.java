package com.example.wallet.repository;

import com.example.wallet.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
