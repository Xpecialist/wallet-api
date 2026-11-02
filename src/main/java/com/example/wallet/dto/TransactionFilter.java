package com.example.wallet.dto;

import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Optional filters for transaction lists, bound from query parameters. Any field left {@code null} is not applied.
 *
 * @param from      earliest creation date, inclusive ({@code yyyy-MM-dd})
 * @param to        latest creation date, inclusive ({@code yyyy-MM-dd})
 * @param minAmount minimum amount, inclusive
 * @param maxAmount maximum amount, inclusive
 * @param status    transaction status
 * @param type      transaction type
 */
public record TransactionFilter(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        TransactionStatus status,
        TransactionType type) {}
