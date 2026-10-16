import { useState } from 'react';
import { newIdempotencyKey } from '../api/client.js';
import { Alert } from './Feedback.jsx';
import { Form, TextField } from './Fields.jsx';

/**
 * Amount-only form (deposit, withdraw, admin deposit). `action(amount, idempotencyKey)` performs the call;
 * `extraFields` renders additional inputs inside the form. Calls `onDone` after a successful submit.
 */
export function AmountForm({ title, submitText, action, onDone, extraFields }) {
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    setSuccess('');
    try {
      const result = await action(amount, newIdempotencyKey());
      setSuccess(`Done (transaction #${result.id}).`);
      setAmount('');
      if (onDone) onDone(result);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>{title}</h2>
      <Form onSubmit={submit} busy={busy} submitText={submitText}>
        {extraFields}
        <TextField
          label="Amount"
          type="number"
          step="0.0001"
          min="0.0001"
          required
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
        />
      </Form>
      <Alert>{error}</Alert>
      <Alert type="success">{success}</Alert>
    </section>
  );
}
