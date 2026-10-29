import { useState } from 'react';
import { Alert } from './Feedback.jsx';
import { Form, TextField } from './Fields.jsx';
import { useIdempotentSubmit } from '../utils/useIdempotentSubmit.js';

/**
 * Amount-only form (deposit, withdraw, admin deposit). `action(amount, idempotencyKey)` performs the call;
 * `extraFields` renders additional inputs inside the form; `extraPayload` holds their values, so that changing one
 * counts as a different request (new Idempotency-Key). Calls `onDone` after a successful submit.
 */
export function AmountForm({ title, submitText, action, onDone, extraFields, extraPayload }) {
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const run = useIdempotentSubmit();

  async function submit() {
    setBusy(true);
    setError('');
    setSuccess('');
    try {
      const result = await run({ amount, ...extraPayload }, (key) => action(amount, key));
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
