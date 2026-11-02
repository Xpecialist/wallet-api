package com.example.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/login}.
 *
 * @param username the account's username, at most 50 characters (the longest a username can be)
 * @param password the raw password
 */
public record LoginRequest(@NotBlank @Size(max = 50) @NoNul String username, @NotBlank String password) {}
