package com.example.wallet.dto;

import com.example.wallet.entity.User;
import java.time.LocalDateTime;

/**
 * User representation returned by the admin API. Does not include the password hash.
 *
 * @param id        user id
 * @param username  username
 * @param email     email address
 * @param role      role name, {@code USER} or {@code ADMIN}
 * @param enabled   whether the user may log in
 * @param createdAt creation timestamp
 */
public record UserResponse(Long id, String username, String email, String role, boolean enabled,
                           LocalDateTime createdAt) {
    /**
     * Maps a user entity to its response form.
     *
     * @param u the user
     * @return the response DTO
     */
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(), u.isEnabled(), u.getCreatedAt());
    }
}
