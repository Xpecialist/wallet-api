package com.example.wallet.service;

import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.ChangePasswordRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.UpdateProfileRequest;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.entity.User;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.security.JwtService;
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
    private final JwtService jwt;

    /**
     * Creates the service.
     *
     * @param users   user repository
     * @param encoder password encoder
     * @param jwt     token service (issues the fresh token after a password change)
     */
    public UserService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
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
        String email = req.email().trim();
        if (!email.equalsIgnoreCase(u.getEmail()) && users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
        u.setEmail(email);
        return UserResponse.from(users.save(u));
    }

    /**
     * Changes the password of the given user after verifying the current one. The new password is hashed with the
     * application's password encoder. Every token issued so far (on all devices) is revoked by bumping the token
     * version in the same transaction, and a fresh token for the new version is returned so the caller's own
     * session survives. The bump's row lock is held until commit, so the version read right after it is exactly
     * the one this call produced even if a logout runs concurrently.
     *
     * @param username the username
     * @param req      the current and new passwords
     * @return a new token valid for the new token version
     * @throws ApiException 404 if the user does not exist; 400 if the current password is wrong or the new password
     *         is the same as the current one
     */
    @Transactional
    public AuthResponse changePassword(String username, ChangePasswordRequest req) {
        User u = find(username);
        if (!encoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from the current password");
        }
        u.setPasswordHash(encoder.encode(req.newPassword()));
        users.save(u);
        users.bumpTokenVersion(u.getId());
        return new AuthResponse(jwt.generateToken(u.getUsername(), users.findTokenVersion(u.getId())));
    }

    /**
     * Lists all users, sorted and paged.
     *
     * @param pageable page number, size and sort
     * @return one page of users
     * @throws ApiException 400 if the sort property is not one of id, username, email, role, enabled, createdAt
     */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listAll(Pageable pageable) {
        SortWhitelist.check(pageable, SortWhitelist.USERS);
        RequestLimits.checkPage(pageable);
        return PageResponse.from(users.findAll(pageable).map(UserResponse::from));
    }

    /**
     * Enables or disables a user. A disabled user cannot log in and their existing tokens are rejected. Disabling
     * also bumps the token version, so the user needs a fresh login (new token) after being re-enabled.
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
        UserResponse saved = UserResponse.from(users.save(u));
        if (!enabled) users.bumpTokenVersion(u.getId());
        return saved;
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
