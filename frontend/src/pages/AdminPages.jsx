import { useState } from 'react';
import { NavLink, Navigate, Outlet } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { AmountForm } from '../components/AmountForm.jsx';
import { DataTable } from '../components/DataTable.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { Form, SelectField, TextField } from '../components/Fields.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { StatusBadge } from '../components/StatusBadge.jsx';
import { TransactionBrowser } from '../components/TransactionBrowser.jsx';
import { formatDate, formatMoney } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

/** Admin section shell with its own sub-navigation. */
export function AdminLayout() {
  return (
    <>
      <h1>Admin</h1>
      <nav className="subnav">
        <NavLink to="/admin/users">Users</NavLink>
        <NavLink to="/admin/transactions">All transactions</NavLink>
        <NavLink to="/admin/deposit-requests">Deposit requests</NavLink>
        <NavLink to="/admin/deposit">Deposit to wallet</NavLink>
      </nav>
      <Outlet />
    </>
  );
}

/** /admin redirects to the users table. */
export const AdminIndex = () => <Navigate to="/admin/users" replace />;

/** Paged users table with an enable/disable toggle. */
export function AdminUsersPage() {
  const { user: me } = useAuth();
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useFetch(() => api.adminUsers({ page, size: 10, sort: 'id,asc' }), [page]);
  const [actionError, setActionError] = useState('');
  const [busyId, setBusyId] = useState(null);

  async function toggle(u) {
    setBusyId(u.id);
    setActionError('');
    try {
      await api.setUserEnabled(u.id, !u.enabled);
      reload();
    } catch (e) {
      setActionError(e.message);
    } finally {
      setBusyId(null);
    }
  }

  const columns = [
    { key: 'id', header: 'ID' },
    { key: 'username', header: 'Username' },
    { key: 'email', header: 'Email' },
    { key: 'role', header: 'Role' },
    { key: 'enabled', header: 'Status', render: (u) => (u.enabled ? 'Enabled' : 'Disabled') },
    { key: 'createdAt', header: 'Created', render: (u) => formatDate(u.createdAt) },
    {
      key: 'actions',
      header: '',
      render: (u) => (
        <button
          type="button"
          className="secondary"
          disabled={busyId === u.id || u.id === me?.id}
          onClick={() => toggle(u)}
        >
          {u.enabled ? 'Disable' : 'Enable'}
        </button>
      ),
    },
  ];

  return (
    <section className="card">
      <h2>Users</h2>
      <Alert>{error}</Alert>
      <Alert>{actionError}</Alert>
      {loading && !data && <Loading />}
      {data && (
        <>
          <DataTable columns={columns} rows={data.content} emptyText="No users." />
          <Pagination data={data} onPage={setPage} />
        </>
      )}
    </section>
  );
}

/** All transactions with the same filters as the user history. */
export function AdminTransactionsPage() {
  return <TransactionBrowser fetcher={api.adminTransactions} />;
}

/** Credit any wallet by id. */
export function AdminDepositPage() {
  const [walletId, setWalletId] = useState('');
  return (
    <div className="narrow">
      <AmountForm
        title="Deposit to wallet"
        submitText="Deposit"
        action={(amount, key) => api.adminDeposit(walletId, amount, key)}
        extraFields={
          <TextField label="Wallet ID" type="number" min="1" step="1" required value={walletId} onChange={(e) => setWalletId(e.target.value)} />
        }
      />
    </div>
  );
}

const REQUEST_STATUS_OPTIONS = [['PENDING', 'Pending'], ['APPROVED', 'Approved'], ['REJECTED', 'Rejected'], ['', 'Any']];

/** Deposit requests with a status filter (default PENDING); approve credits the user's wallet, reject needs a reason. */
export function AdminDepositRequestsPage() {
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useFetch(
    () => api.adminDepositRequests({ page, size: 10, status }),
    [page, status]
  );
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [success, setSuccess] = useState('');
  const [rejecting, setRejecting] = useState(null); // the request being rejected
  const [reason, setReason] = useState('');

  async function run(call, doneText) {
    setBusy(true);
    setActionError('');
    setSuccess('');
    try {
      await call();
      setSuccess(doneText);
      setRejecting(null);
      setReason('');
    } catch (e) {
      setActionError(e.message);
    } finally {
      setBusy(false);
      reload(); // also after an error such as "already decided", so the list shows the real state
    }
  }

  const approve = (r) => run(() => api.approveDepositRequest(r.id), `Request #${r.id} approved.`);
  const reject = () => run(() => api.rejectDepositRequest(rejecting.id, reason.trim()), `Request #${rejecting.id} rejected.`);

  const columns = [
    { key: 'id', header: 'ID' },
    { key: 'username', header: 'User' },
    { key: 'walletId', header: 'Wallet' },
    { key: 'amount', header: 'Amount', render: (r) => formatMoney(r.amount) },
    { key: 'note', header: 'Note', render: (r) => r.note || '-' },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
    { key: 'createdAt', header: 'Requested', render: (r) => formatDate(r.createdAt) },
    { key: 'decisionNote', header: 'Decision note', render: (r) => r.decisionNote || '-' },
    {
      key: 'actions',
      header: '',
      render: (r) =>
        r.status === 'PENDING' && (
          <div className="row-actions">
            <button type="button" disabled={busy} onClick={() => approve(r)}>
              Approve
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setRejecting(r)}>
              Reject
            </button>
          </div>
        ),
    },
  ];

  return (
    <section className="card">
      <h2>Deposit requests</h2>
      <div className="filters">
        <SelectField
          label="Status"
          options={REQUEST_STATUS_OPTIONS}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        />
      </div>
      <Alert>{error}</Alert>
      <Alert>{actionError}</Alert>
      <Alert type="success">{success}</Alert>
      {loading && !data && <Loading />}
      {data && (
        <>
          <DataTable columns={columns} rows={data.content} emptyText="No deposit requests." />
          <Pagination data={data} onPage={setPage} />
        </>
      )}
      {rejecting && (
        <div className="reject-box">
          <Form onSubmit={reject} busy={busy} submitText="Confirm reject">
            <TextField
              label={`Reason for rejecting request #${rejecting.id} (required)`}
              maxLength={255}
              required
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Form>
          <button type="button" className="secondary" disabled={busy} onClick={() => setRejecting(null)}>
            Cancel
          </button>
        </div>
      )}
    </section>
  );
}
