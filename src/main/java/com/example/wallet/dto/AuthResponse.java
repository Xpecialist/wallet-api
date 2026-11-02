package com.example.wallet.dto;

/**
 * Response returned after successful registration or login.
 *
 * @param token     the signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType the token scheme, always {@code "Bearer"} when built with the convenience constructor
 */
public record AuthResponse(String token, String tokenType) {
    /**
     * Creates a response with token type {@code "Bearer"}.
     *
     * @param token the signed JWT
     */
    public AuthResponse(String token) { this(token, "Bearer"); }
}
