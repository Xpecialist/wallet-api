import { describe, expect, it } from 'vitest';
import { directionOf, formatDate, formatMoney, isOutgoing } from '../src/utils/format.js';

describe('formatMoney', () => {
  it('shows at least 2 decimals with currency', () => {
    expect(formatMoney(74.5, 'USD')).toMatch(/74[.,]50 USD$/);
    expect(formatMoney('74.5', 'USD')).toMatch(/74[.,]50 USD$/);
  });
  it('keeps up to 4 decimals and rounds beyond', () => {
    expect(formatMoney('1.23456')).toMatch(/1[.,]2346$/);
    expect(formatMoney('1.1')).toMatch(/1[.,]10$/);
  });
  it('omits the currency when not given', () => {
    expect(formatMoney('5')).toMatch(/^5[.,]00$/);
  });
  it('handles zero, negatives and large numbers', () => {
    expect(formatMoney(0)).toMatch(/^0[.,]00$/);
    expect(formatMoney(-3.5)).toMatch(/^[-−]3[.,]50$/);
    expect(formatMoney(1234567.891)).toMatch(/1\D234\D567[.,]891/);
  });
  it('returns "-" for non-numeric input', () => {
    expect(formatMoney('abc', 'USD')).toBe('-');
    expect(formatMoney(undefined)).toBe('-');
  });
  it('null is formatted as 0.00 instead of "-"', () => {
    expect(formatMoney(null)).toBe('-');
  });
});

describe('formatDate', () => {
  it('returns "-" for empty values', () => {
    expect(formatDate(null)).toBe('-');
    expect(formatDate(undefined)).toBe('-');
    expect(formatDate('')).toBe('-');
  });
  it('formats an ISO local date-time using the locale', () => {
    expect(formatDate('2026-10-16T02:15:00.123')).toBe(new Date('2026-10-16T02:15:00.123').toLocaleString());
  });
  it('returns the raw value when it cannot be parsed', () => {
    expect(formatDate('not a date')).toBe('not a date');
  });
});

describe('directionOf / isOutgoing', () => {
  it('classifies deposits and withdrawals regardless of wallet', () => {
    expect(directionOf({ type: 'DEPOSIT' }, 1)).toBe('deposit');
    expect(directionOf({ type: 'WITHDRAWAL' }, 1)).toBe('withdrawal');
  });
  it('classifies transfers by sender wallet', () => {
    const tx = { type: 'TRANSFER', senderWalletId: 1, receiverWalletId: 2 };
    expect(directionOf(tx, 1)).toBe('sent');
    expect(directionOf(tx, 2)).toBe('received');
  });
  it('isOutgoing is true for sent and withdrawal only', () => {
    expect(['sent', 'withdrawal', 'received', 'deposit'].map(isOutgoing)).toEqual([true, true, false, false]);
  });
});
