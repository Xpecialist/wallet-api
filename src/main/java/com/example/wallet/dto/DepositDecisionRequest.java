package com.example.wallet.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/deposit-requests/{id}/approve} (optional) and {@code .../reject} (the note is
 * required there, checked by the service).
 *
 * @param decisionNote the admin note, at most 255 characters
 */
public record DepositDecisionRequest(@Size(max = 255) @PlainText String decisionNote) {}
