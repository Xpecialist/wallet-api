import { api } from '../api/client.js';
import { directionOf, formatDate, formatMoney } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';
import { Alert, Loading } from './Feedback.jsx';

/** Loads and shows one transaction (GET /api/transactions/{id}) in a panel with a close button. */
export function TransactionDetail({ id, walletId, currency, onClose }) {
  const { data: tx, error, loading } = useFetch(() => api.transaction(id), [id]);

  return (
    <section className="card">
      <div className="card-head">
        <h2>Transaction #{id}</h2>
        <button type="button" className="link" onClick={onClose}>
          Close
        </button>
      </div>
      {loading && <Loading />}
      <Alert>{error}</Alert>
      {tx && (
        <dl className="details">
          <dt>Type</dt>
          <dd>
            {tx.type}
            {walletId != null && ` (${directionOf(tx, walletId)})`}
          </dd>
          <dt>Amount</dt>
          <dd>{formatMoney(tx.amount, currency)}</dd>
          <dt>Status</dt>
          <dd>{tx.status}</dd>
          <dt>From wallet</dt>
          <dd>{tx.senderWalletId ?? '-'}</dd>
          <dt>To wallet</dt>
          <dd>{tx.receiverWalletId ?? '-'}</dd>
          <dt>Date</dt>
          <dd>{formatDate(tx.createdAt)}</dd>
          <dt>Note</dt>
          <dd>{tx.note || '-'}</dd>
        </dl>
      )}
    </section>
  );
}
