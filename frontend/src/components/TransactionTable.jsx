import { directionOf, formatDate, formatMoney, isOutgoing } from '../utils/format.js';
import { DataTable } from './DataTable.jsx';

/**
 * Transactions table. With `walletId` the rows show a direction (sent / received / deposit / withdrawal)
 * and a signed amount relative to that wallet; without it (admin view) the plain type and both wallets are shown.
 */
export function TransactionTable({ rows, walletId, currency, onRowClick }) {
  const columns = [
    { key: 'id', header: '#' },
    { key: 'createdAt', header: 'Date', render: (t) => formatDate(t.createdAt) },
    walletId != null
      ? {
          key: 'direction',
          header: 'Direction',
          render: (t) => <span className={`tag tag-${directionOf(t, walletId)}`}>{directionOf(t, walletId)}</span>,
        }
      : { key: 'type', header: 'Type' },
    {
      key: 'amount',
      header: 'Amount',
      render: (t) => {
        if (walletId == null) return formatMoney(t.amount, currency);
        const sign = isOutgoing(directionOf(t, walletId)) ? '-' : '+';
        return `${sign}${formatMoney(t.amount, currency)}`;
      },
    },
    { key: 'senderWalletId', header: 'From', render: (t) => t.senderWalletId ?? '-' },
    { key: 'receiverWalletId', header: 'To', render: (t) => t.receiverWalletId ?? '-' },
    { key: 'status', header: 'Status' },
    { key: 'note', header: 'Note', render: (t) => t.note || '' },
  ];
  return <DataTable columns={columns} rows={rows} onRowClick={onRowClick} emptyText="No transactions." />;
}
