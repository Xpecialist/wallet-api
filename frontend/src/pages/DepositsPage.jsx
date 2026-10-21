import { useEffect, useRef, useState } from 'react';
import { api, newIdempotencyKey } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { DataTable } from '../components/DataTable.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { Form, SelectField, TextField } from '../components/Fields.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { StatusBadge } from '../components/StatusBadge.jsx';
import { formatDate, formatMoney } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

const STATUS_OPTIONS = [['', 'Any'], ['PENDING', 'Pending'], ['APPROVED', 'Approved'], ['REJECTED', 'Rejected']];

/** Ask for money to be added (an admin must approve) and follow the status of your requests. */
export default function DepositsPage() {
  const { wallet, refreshWallet } = useAuth();
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const { data, error: listError, loading, reload } = useFetch(
    () => api.depositRequests({ page, size: 10, sort: 'createdAt,desc', status }),
    [page, status]
  );

  // An approved request changes the balance without any action here, so pick it up when the page opens.
  useEffect(() => {
    refreshWallet().catch(() => {});
  }, [refreshWallet]);

  async function submit() {
    if (inFlight.current) return; // a second click or Enter before the first call finished
    inFlight.current = true;
    setBusy(true);
    setError('');
    setSuccess('');
    const body = { amount };
    if (note.trim()) body.note = note.trim();
    try {
      const created = await api.createDepositRequest(body, newIdempotencyKey());
      setSuccess(`Request #${created.id} sent. Your balance changes only after an admin approves it.`);
      setAmount('');
      setNote('');
      if (page === 0) reload();
      else setPage(0);
    } catch (e) {
      setError(e.message);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const columns = [
    { key: 'id', header: 'ID' },
    { key: 'amount', header: 'Amount', render: (r) => formatMoney(r.amount, wallet?.currency) },
    { key: 'note', header: 'Note', render: (r) => r.note || '-' },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
    { key: 'createdAt', header: 'Requested', render: (r) => formatDate(r.createdAt) },
    { key: 'decidedAt', header: 'Decided', render: (r) => formatDate(r.decidedAt) },
    { key: 'decisionNote', header: 'Decision note', render: (r) => r.decisionNote || '-' },
  ];

  return (
    <>
      <h1>Deposits</h1>
      <section className="card narrow">
        <h2>Request a deposit</h2>
        <p className="muted">
          Money is never added automatically. Send your payment (for example a bank transfer), put its reference
          in the note, and an admin will approve the request once the funds have arrived.
        </p>
        <Form onSubmit={submit} busy={busy} submitText="Send request">
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
            label={`Note / payment reference (optional, ${note.length}/255)`}
            maxLength={255}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </Form>
        <Alert>{error}</Alert>
        <Alert type="success">{success}</Alert>
      </section>

      <section className="card">
        <h2>My requests</h2>
        <div className="filters">
          <SelectField
            label="Status"
            options={STATUS_OPTIONS}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <Alert>{listError}</Alert>
        {loading && !data && <Loading />}
        {data && (
          <>
            <DataTable columns={columns} rows={data.content} emptyText="You have no deposit requests." />
            <Pagination data={data} onPage={setPage} />
          </>
        )}
      </section>
    </>
  );
}
