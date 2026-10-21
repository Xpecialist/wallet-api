import { Link } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { AmountForm } from '../components/AmountForm.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { TransactionTable } from '../components/TransactionTable.jsx';
import { formatMoney } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

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
      <section className="card balance">
        <span className="muted">Current balance</span>
        <strong className="balance-amount">{formatMoney(wallet.balance, wallet.currency)}</strong>
        <span className="muted">Wallet ID {wallet.id}</span>
      </section>

      <section className="card">
        <h2>Add funds</h2>
        <p className="muted">
          Money is added only after an admin approves your request. <Link to="/deposits">Request a deposit</Link>
        </p>
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
          <TransactionTable rows={recent.data.content} walletId={wallet.id} currency={wallet.currency} />
        )}
      </section>
    </>
  );
}
