package com.example.wallet.controller;

import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.LoginRequest;
import com.example.wallet.dto.RegisterRequest;
import com.example.wallet.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Endpoints under {@code /api/auth}: public registration and login, authenticated logout. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    /** @param authService the authentication service */
    public AuthController(AuthService authService) { this.authService = authService; }

    /**
     * {@code POST /api/auth/register}. Public. Creates a user with a wallet and returns a JWT.
     *
     * @param req the registration data
     * @return 201 with the token
     *         (errors: 400 on validation failure, 409 if the username or email is taken)
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    /**
     * {@code POST /api/auth/login}. Public. Verifies credentials and returns a JWT.
     *
     * @param req the credentials
     * @return 200 with the token (errors: 400 on validation failure, 401 on wrong username or password)
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /**
     * {@code POST /api/auth/logout}. Authenticated. Logs the caller out of ALL devices by revoking every token
     * issued so far (the token used for this call included).
     *
     * @param auth the authenticated caller
     * @return 204 with no body (errors: 401 if not authenticated or the token is already revoked)
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication auth) {
        authService.logout(auth.getName());
        return ResponseEntity.noContent().build();
    }
}
