package com.example.wallet.entity;

/** Lifecycle status of a {@link Transaction}. Transfers are currently only ever stored as {@code COMPLETED}. */
public enum TransactionStatus { PENDING, COMPLETED, FAILED }
