import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { TransactionBrowser } from '../components/TransactionBrowser.jsx';

/** The signed-in user's transaction history. */
export default function HistoryPage() {
  const { wallet } = useAuth();
  return (
    <>
      <h1>History</h1>
      <TransactionBrowser fetcher={api.transactions} walletId={wallet?.id} currency={wallet?.currency} />
    </>
  );
}
