package com.example.wallet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /api/users/me}.
 *
 * @param email the new, well-formed and unique email address
 */
public record UpdateProfileRequest(@NotBlank @Email @PlainText String email) {}
