package com.example.wallet.controller;

import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.ChangePasswordRequest;
import com.example.wallet.dto.UpdateProfileRequest;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.exception.ApiException;
import com.example.wallet.security.AuthUser;
import com.example.wallet.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Authenticated endpoints under {@code /api/users/me} for managing the caller's own profile. */
@RestController
@RequestMapping("/api/users/me")
public class UserController {
    private final UserService userService;

    /** @param userService the user service */
    public UserController(UserService userService) { this.userService = userService; }

    /**
     * {@code GET /api/users/me}. Any authenticated user. Returns the caller's profile.
     *
     * @param auth the authenticated caller
     * @return the profile (errors: 401 if not authenticated)
     */
    @GetMapping
    public UserResponse me(Authentication auth) { return userService.getProfile(auth.getName()); }

    /**
     * {@code PATCH /api/users/me}. Any authenticated user. Changes the caller's email address.
     *
     * @param req  the new email
     * @param auth the authenticated caller
     * @return the updated profile (errors: 400 on validation failure, 401 if not authenticated,
     *         409 if the email is already registered)
     */
    @PatchMapping
    public UserResponse update(@Valid @RequestBody UpdateProfileRequest req, Authentication auth) {
        return userService.updateEmail(auth.getName(), req);
    }

    /**
     * {@code POST /api/users/me/password}. Any authenticated user. Changes the caller's password.
     *
     * @param req  the current and new passwords
     * @param auth the authenticated caller
     * @return 200 with a NEW token; all older tokens (this one included, and those of other devices) stop working
     *         (errors: 400 on validation failure or wrong current password, 401 if not authenticated)
     */
    @PostMapping("/password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication auth) {
        if (!(auth.getPrincipal() instanceof AuthUser caller)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userService.changePassword(auth.getName(), caller.getTokenVersion(), req);
    }
}
