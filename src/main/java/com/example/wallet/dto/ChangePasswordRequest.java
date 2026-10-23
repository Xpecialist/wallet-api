package com.example.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/users/me/password}.
 *
 * @param currentPassword the caller's current raw password
 * @param newPassword     the new raw password, 8 to 72 characters and at most 72 bytes in UTF-8
 */
public record ChangePasswordRequest(
        @NotBlank @MaxUtf8Bytes(72) String currentPassword,
        @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String newPassword) {}
