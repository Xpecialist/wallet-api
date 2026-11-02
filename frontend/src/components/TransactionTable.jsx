import { directionOf, formatDate, formatMoney, isOutgoing } from '../utils/format.js';
import { DataTable } from './DataTable.jsx';
import { StatusBadge } from './StatusBadge.jsx';

/**
 * Transactions table. With `walletId` the rows show a direction (sent / received / deposit / withdrawal)
 * and a signed amount relative to that wallet; without it (admin view) the plain type and both wallets are shown.
 */
export function TransactionTable({ rows, walletId, currency, onRowClick }) {
  const columns = [
    { key: 'id', header: '#', numeric: true, cellClass: 'cell-muted' },
    { key: 'createdAt', header: 'Date', render: (t) => formatDate(t.createdAt), cellClass: 'cell-muted' },
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
      numeric: true,
      render: (t) => {
        if (walletId == null) return formatMoney(t.amount, currency);
        const out = isOutgoing(directionOf(t, walletId));
        return <span className={out ? 'amount-out' : 'amount-in'}>{`${out ? '-' : '+'}${formatMoney(t.amount, currency)}`}</span>;
      },
    },
    { key: 'senderWalletId', header: 'From', render: (t) => t.senderWalletId ?? '-' },
    { key: 'receiverWalletId', header: 'To', render: (t) => t.receiverWalletId ?? '-' },
    { key: 'status', header: 'Status', render: (t) => <StatusBadge status={t.status} /> },
    { key: 'note', header: 'Note', render: (t) => t.note || '', cellClass: 'cell-note' },
  ];
  return <DataTable columns={columns} rows={rows} onRowClick={onRowClick} emptyText="No transactions." />;
}
