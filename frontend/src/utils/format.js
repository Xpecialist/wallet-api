/** Formats an amount with 2 to 4 decimals plus the currency code, e.g. "74.50 USD". */
export function formatMoney(value, currency = '') {
  if (value === null || value === undefined || value === '') return '-';
  const n = Number(value);
  if (Number.isNaN(n)) return '-';
  const text = n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
  return currency ? `${text} ${currency}` : text;
}

/** Formats an ISO local date-time for display. */
export function formatDate(value) {
  if (!value) return '-';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString();
}

/**
 * Direction of a transaction relative to the given wallet id:
 * "deposit", "withdrawal", "sent" or "received".
 */
export function directionOf(tx, walletId) {
  if (tx.type === 'DEPOSIT') return 'deposit';
  if (tx.type === 'WITHDRAWAL') return 'withdrawal';
  return tx.senderWalletId === walletId ? 'sent' : 'received';
}

/** True when the money leaves the wallet for this direction. */
export const isOutgoing = (direction) => direction === 'sent' || direction === 'withdrawal';
