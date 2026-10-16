import { Link } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { AmountForm } from '../components/AmountForm.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { TransactionTable } from '../components/TransactionTable.jsx';
import { formatMoney } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

/** Balance, deposit/withdraw forms and the latest transactions. */
export default function DashboardPage() {
  const { wallet, refreshWallet } = useAuth();
  const recent = useFetch(() => api.transactions({ size: 5, sort: 'createdAt,desc' }), []);

  // Refresh balance and the recent list after a deposit or withdrawal.
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

      <div className="grid-2">
        <AmountForm title="Deposit" submitText="Deposit" action={api.deposit} onDone={afterChange} />
        <AmountForm title="Withdraw" submitText="Withdraw" action={api.withdraw} onDone={afterChange} />
      </div>

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
