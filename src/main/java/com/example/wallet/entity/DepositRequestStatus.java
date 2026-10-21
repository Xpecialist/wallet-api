package com.example.wallet.entity;

/** Lifecycle of a {@link DepositRequest}: created {@code PENDING}, then decided once by an admin. */
public enum DepositRequestStatus { PENDING, APPROVED, REJECTED }
