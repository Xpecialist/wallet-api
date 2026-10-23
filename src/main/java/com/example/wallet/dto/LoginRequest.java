package com.example.wallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/auth/login}.
 *
 * @param username the account's username
 * @param password the raw password
 */
public record LoginRequest(@NotBlank @NoNul String username, @NotBlank String password) {}
