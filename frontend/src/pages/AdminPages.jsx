import { useState } from 'react';
import { NavLink, Navigate, Outlet } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { AmountForm } from '../components/AmountForm.jsx';
import { DataTable } from '../components/DataTable.jsx';
import { Alert, Loading } from '../components/Feedback.jsx';
import { TextField } from '../components/Fields.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { TransactionBrowser } from '../components/TransactionBrowser.jsx';
import { formatDate } from '../utils/format.js';
import { useFetch } from '../utils/useFetch.js';

/** Admin section shell with its own sub-navigation. */
export function AdminLayout() {
  return (
    <>
      <h1>Admin</h1>
      <nav className="subnav">
        <NavLink to="/admin/users">Users</NavLink>
        <NavLink to="/admin/transactions">All transactions</NavLink>
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
