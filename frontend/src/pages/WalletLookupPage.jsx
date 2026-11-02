import { useState } from 'react';
import { api } from '../api/client.js';
import { Alert } from '../components/Feedback.jsx';
import { Form, TextField } from '../components/Fields.jsx';
import { formatMoney } from '../utils/format.js';

/** Look up a wallet by id (allowed for the owner or an admin). */
export default function WalletLookupPage() {
  const [id, setId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);

  async function submit() {
    setBusy(true);
    setError('');
    setResult(null);
    try {
      setResult(await api.wallet(id));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Wallet lookup</h1>
      <section className="card narrow">
        <Form onSubmit={submit} busy={busy} submitText="Look up">
          <TextField label="Wallet ID" type="number" min="1" step="1" required value={id} onChange={(e) => setId(e.target.value)} />
        </Form>
        <Alert>{error}</Alert>
        {result && (
          <dl className="details">
            <dt>Wallet ID</dt>
            <dd>{result.id}</dd>
            <dt>Owner</dt>
            <dd>{result.username} (user #{result.userId})</dd>
            <dt>Balance</dt>
            <dd>{formatMoney(result.balance, result.currency)}</dd>
          </dl>
        )}
      </section>
    </>
  );
}
