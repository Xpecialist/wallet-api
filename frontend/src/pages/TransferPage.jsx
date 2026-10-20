import { useState } from 'react';
import { api, newIdempotencyKey } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { Alert } from '../components/Feedback.jsx';
import { Form, SelectField, TextField } from '../components/Fields.jsx';
import { formatMoney } from '../utils/format.js';

/** Send money to a username or a wallet id, with an optional note. */
export default function TransferPage() {
  const { wallet, refreshWallet } = useAuth();
  const [mode, setMode] = useState('username');
  const [receiver, setReceiver] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit() {
    setError('');
    setSuccess('');
    const target = receiver.trim();
    if (!target) {
      setError(mode === 'username' ? 'Receiver username is required.' : 'Receiver wallet ID is required.');
      return;
    }
    setBusy(true);
    const body = { amount };
    if (mode === 'username') body.receiverUsername = target;
    else body.receiverWalletId = Number(target);
    if (note.trim()) body.note = note.trim();
    try {
      const tx = await api.transfer(body, newIdempotencyKey());
      setSuccess(`Sent ${formatMoney(tx.amount, wallet?.currency)} (transaction #${tx.id}).`);
      setReceiver('');
      setAmount('');
      setNote('');
      refreshWallet();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Transfer</h1>
      <section className="card narrow">
        {wallet && <p className="muted">Available: {formatMoney(wallet.balance, wallet.currency)}</p>}
        <Form onSubmit={submit} busy={busy} submitText="Send">
          <SelectField
            label="Send to"
            options={[['username', 'Username'], ['walletId', 'Wallet ID']]}
            value={mode}
            onChange={(e) => {
              setMode(e.target.value);
              setReceiver('');
            }}
          />
          <TextField
            label={mode === 'username' ? 'Receiver username' : 'Receiver wallet ID'}
            type={mode === 'username' ? 'text' : 'number'}
            min={mode === 'username' ? undefined : 1}
            step={mode === 'username' ? undefined : 1}
            required
            value={receiver}
            onChange={(e) => setReceiver(e.target.value)}
          />
          <TextField
            label="Amount"
            type="number"
            step="0.0001"
            min="0.0001"
            required
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
          <TextField
            label={`Note (optional, ${note.length}/255)`}
            maxLength={255}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </Form>
        <Alert>{error}</Alert>
        <Alert type="success">{success}</Alert>
      </section>
    </>
  );
}
