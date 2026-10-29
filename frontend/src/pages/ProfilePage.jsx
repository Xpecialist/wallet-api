import { useState } from 'react';
import { api, setToken } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { Alert } from '../components/Feedback.jsx';
import { Form, TextField } from '../components/Fields.jsx';
import { formatDate } from '../utils/format.js';

function ChangeEmailForm({ user, onChanged }) {
  const [email, setEmail] = useState(user.email);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    setSuccess('');
    try {
      await api.updateEmail(email.trim());
      await onChanged();
      setSuccess('Email updated.');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>Change email</h2>
      <Form onSubmit={submit} busy={busy} submitText="Save email">
        <TextField label="Email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      </Form>
      <Alert>{error}</Alert>
      <Alert type="success">{success}</Alert>
    </section>
  );
}

function ChangePasswordForm() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  async function submit() {
    setBusy(true);
    setError('');
    setSuccess('');
    try {
      const res = await api.changePassword(current, next);
      // the old token (this session's too) is revoked by the change: keep the session with the fresh one
      if (res?.token) setToken(res.token);
      setCurrent('');
      setNext('');
      setSuccess('Password changed.');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>Change password</h2>
      <Form onSubmit={submit} busy={busy} submitText="Change password">
        <TextField
          label="Current password"
          type="password"
          required
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
        />
        <TextField
          label="New password (8-72 characters)"
          type="password"
          required
          minLength={8}
          maxLength={72}
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
      </Form>
      <Alert>{error}</Alert>
      <Alert type="success">{success}</Alert>
    </section>
  );
}

/** Profile details, email change and password change. */
export default function ProfilePage() {
  const { user, refreshUser } = useAuth();
  if (!user) return <Alert>Could not load your profile.</Alert>;
  return (
    <>
      <h1>Profile</h1>
      <section className="card">
        <dl className="details">
          <dt>Username</dt>
          <dd>{user.username}</dd>
          <dt>Email</dt>
          <dd>{user.email}</dd>
          <dt>Role</dt>
          <dd>{user.role}</dd>
          <dt>Member since</dt>
          <dd>{formatDate(user.createdAt)}</dd>
        </dl>
      </section>
      <div className="grid-2 grid-equal">
        <ChangeEmailForm user={user} onChanged={refreshUser} />
        <ChangePasswordForm />
      </div>
    </>
  );
}
