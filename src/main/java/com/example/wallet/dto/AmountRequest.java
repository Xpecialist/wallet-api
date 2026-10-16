package com.example.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for deposits and withdrawals.
 *
 * @param amount amount to deposit or withdraw; at least 0.0001, up to 15 integer and 4 fraction digits
 */
public record AmountRequest(
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount) {}
