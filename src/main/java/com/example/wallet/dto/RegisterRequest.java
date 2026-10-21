package com.example.wallet.dto;

import jakarta.validation.constraints.*;

/**
 * Request body for {@code POST /api/auth/register}.
 *
 * @param username desired unique username, 3 to 50 characters
 * @param email    unique, well-formed email address
 * @param password raw password, 8 to 72 characters and at most 72 bytes in UTF-8 (BCrypt's limit)
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password) {}
