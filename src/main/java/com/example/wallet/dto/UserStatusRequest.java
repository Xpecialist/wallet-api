package com.example.wallet.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/admin/users/{id}/status}.
 *
 * @param enabled {@code true} to enable the user, {@code false} to disable them
 */
public record UserStatusRequest(@NotNull Boolean enabled) {}
