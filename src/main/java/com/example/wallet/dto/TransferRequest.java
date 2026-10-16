package com.example.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transactions/transfer}. Provide either {@code receiverUsername} or
 * {@code receiverWalletId}; if both are given, {@code receiverWalletId} is used.
 *
 * @param receiverUsername username of the receiving user, or {@code null}
 * @param receiverWalletId id of the receiving wallet, or {@code null}
 * @param amount           amount to transfer; at least 0.0001, up to 15 integer and 4 fraction digits
 * @param note             optional note stored on the transaction, at most 255 characters
 */
public record TransferRequest(
        String receiverUsername,
        Long receiverWalletId,
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 255) String note) {}
