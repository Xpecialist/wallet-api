package com.example.wallet.entity;

/**
 * Kind of a {@link Transaction}. A {@code TRANSFER} has both a sender and a receiver wallet, a {@code DEPOSIT} has
 * only a receiver wallet, and a {@code WITHDRAWAL} has only a sender wallet.
 */
public enum TransactionType { TRANSFER, DEPOSIT, WITHDRAWAL }
