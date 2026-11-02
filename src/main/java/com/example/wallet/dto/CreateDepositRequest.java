package com.example.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/deposit-requests}.
 *
 * @param amount amount to add; at least 0.0001, up to 15 integer and 4 fraction digits (same as a deposit)
 * @param note   optional note, for example the bank transfer reference, at most 255 characters
 */
public record CreateDepositRequest(
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 255) @PlainText String note) {}
