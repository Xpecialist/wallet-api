package com.example.wallet.service;

import com.example.wallet.dto.ChangePasswordRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.UpdateProfileRequest;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.entity.User;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile management for the current user and user administration. */
@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    /**
     * Creates the service.
     *
     * @param users   user repository
     * @param encoder password encoder
     */
    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * Returns the profile of the given user.
     *
     * @param username the username
     * @return the user
     * @throws ApiException 404 if the user does not exist
     */
    @Transactional(readOnly = true)
    public UserResponse getProfile(String username) {
        return UserResponse.from(find(username));
    }

    /**
     * Changes the email address of the given user.
     *
     * @param username the username
     * @param req      the new email
     * @return the updated user
     * @throws ApiException 404 if the user does not exist; 409 if the email belongs to another user
     */
    @Transactional
    public UserResponse updateEmail(String username, UpdateProfileRequest req) {
        User u = find(username);
        if (!req.email().equalsIgnoreCase(u.getEmail()) && users.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
        u.setEmail(req.email());
        return UserResponse.from(users.save(u));
    }

    /**
     * Changes the password of the given user after verifying the current one. The new password is hashed with the
     * application's password encoder.
     *
     * @param username the username
     * @param req      the current and new passwords
     * @throws ApiException 404 if the user does not exist; 400 if the current password is wrong
     */
    @Transactional
    public void changePassword(String username, ChangePasswordRequest req) {
        User u = find(username);
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        u.setPasswordHash(encoder.encode(req.newPassword()));
        users.save(u);
    }

    /**
     * Lists all users, sorted and paged.
     *
     * @param pageable page number, size and sort
     * @return one page of users
     */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listAll(Pageable pageable) {
        return PageResponse.from(users.findAll(pageable).map(UserResponse::from));
    }

    /**
     * Enables or disables a user. A disabled user cannot log in and their existing tokens are rejected.
     *
     * @param id         the id of the user to change
     * @param enabled    the new status
     * @param callerName the authenticated admin making the change
     * @return the updated user
     * @throws ApiException 404 if the user does not exist; 400 if the admin tries to disable their own account
     */
    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled, String callerName) {
        User u = users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!enabled && u.getUsername().equals(callerName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot disable your own account");
        }
        u.setEnabled(enabled);
        return UserResponse.from(users.save(u));
    }

    /**
     * Loads a user by username.
     *
     * @param username the username
     * @return the user
     * @throws ApiException 404 if the user does not exist
     */
    private User find(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
