import { Link } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { AmountForm } from '../components/AmountForm.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { directionOf, formatDate, formatMoney, isOutgoing } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

/** Compact list of the latest transactions with a direction tag, signed amount and muted date. */
function RecentTransactions({ rows, walletId, currency }) {
  if (!rows || rows.length === 0) return <p className="empty">No transactions.</p>;
  return (
    <ul className="tx-list">
      {rows.map((t) => {
        const direction = directionOf(t, walletId);
        const out = isOutgoing(direction);
        return (
          <li key={t.id} className="tx-item">
            <span className={`tag tag-${direction}`}>{direction}</span>
            <span className="tx-main">
              {t.note && <span className="tx-note" title={t.note}>{t.note}</span>}
              <span className="tx-date">{formatDate(t.createdAt)}</span>
            </span>
            <span className={`tx-amount ${out ? 'amount-out' : 'amount-in'}`}>
              {`${out ? '-' : '+'}${formatMoney(t.amount, currency)}`}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** Balance, withdraw form and the latest transactions. Money is added only when an admin approves a deposit request. */
export default function DashboardPage() {
  const { wallet, refreshWallet } = useAuth();
  const recent = useFetch(() => api.transactions({ size: 5, sort: 'createdAt,desc' }), []);

  // Refresh balance and the recent list after a withdrawal.
  const afterChange = () => {
    refreshWallet();
    recent.reload();
  };

  if (!wallet) return <Alert>Could not load your wallet.</Alert>;

  return (
    <>
      <h1>Dashboard</h1>
      <section className="card balance-card">
        <div className="balance">
          <span className="balance-label">Current balance</span>
          <strong className="balance-amount">{formatMoney(wallet.balance, wallet.currency)}</strong>
          <span className="balance-wallet">Wallet ID {wallet.id}</span>
          <span className="balance-hint">
            Money is added only after an admin approves your request. <Link to="/deposits">Request a deposit</Link>
          </span>
        </div>
        <div className="quick-actions">
          <Link to="/transfer" className="btn">Transfer</Link>
          <Link to="/deposits" className="btn secondary">Add funds</Link>
        </div>
      </section>

      <AmountForm title="Withdraw" submitText="Withdraw" action={api.withdraw} onDone={afterChange} />

      <section className="card">
        <div className="card-head">
          <h2>Latest transactions</h2>
          <Link to="/history">View all</Link>
        </div>
        {recent.loading && !recent.data && <Loading />}
        <Alert>{recent.error}</Alert>
        {recent.data && (
          <RecentTransactions rows={recent.data.content} walletId={wallet.id} currency={wallet.currency} />
        )}
      </section>
    </>
  );
}
