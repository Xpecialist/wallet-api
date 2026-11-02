import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { Alert } from '../components/Feedback.jsx';
import { Form, TextField } from '../components/Fields.jsx';
import { BrandMark } from '../components/Layout.jsx';

/** Shared shell for the login and register forms. */
function AuthPage({ title, submitText, request, fields, footer, notice }) {
  const { authenticated, signIn } = useAuth();
  const navigate = useNavigate();
  const [values, setValues] = useState({ username: '', email: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  if (authenticated) return <Navigate to="/" replace />;

  async function submit() {
    setBusy(true);
    setError('');
    try {
      const { token } = await request(values);
      signIn(token);
      navigate('/', { replace: true });
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-brand">
        <BrandMark />
        <span>Wallet</span>
      </div>
      <section className="card">
        <h1>{title}</h1>
        <Form onSubmit={submit} busy={busy} submitText={submitText}>
          {fields.map((f) => (
            <TextField
              key={f.name}
              label={f.label}
              type={f.type || 'text'}
              required
              minLength={f.minLength}
              maxLength={f.maxLength}
              pattern={f.pattern}
              title={f.title}
              autoComplete={f.autoComplete}
              value={values[f.name]}
              onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
            />
          ))}
        </Form>
        <Alert>{error}</Alert>
        <Alert type="warning">{notice}</Alert>
        <p className="muted">{footer}</p>
      </section>
    </div>
  );
}

/** Shown on the login page after a sign-out whose server call failed (the token may still be valid elsewhere). */
const LOGOUT_NOTICE =
  'You were signed out on this device, but the server could not be reached to end your session everywhere. ' +
  'If this is a shared computer, sign in and out again or change your password.';

/** Login page (POST /api/auth/login). */
export function LoginPage() {
  const { logoutNotice, dismissLogoutNotice } = useAuth();
  // the notice lasts until the user leaves this page (or signs in), so it never lingers across navigation
  useEffect(() => dismissLogoutNotice, [dismissLogoutNotice]);
  return (
    <AuthPage
      title="Log in"
      submitText="Log in"
      request={(v) => api.login(v.username, v.password)}
      fields={[
        { name: 'username', label: 'Username', autoComplete: 'username' },
        { name: 'password', label: 'Password', type: 'password', autoComplete: 'current-password' },
      ]}
      notice={logoutNotice ? LOGOUT_NOTICE : ''}
      footer={<>No account? <Link to="/register">Register</Link></>}
    />
  );
}

/** Registration page (POST /api/auth/register); signs the user in on success. */
export function RegisterPage() {
  return (
    <AuthPage
      title="Create account"
      submitText="Register"
      request={(v) => api.register(v.username, v.email, v.password)}
      fields={[
        {
          name: 'username',
          label: 'Username (3-50: letters, digits, . _ -)',
          minLength: 3,
          maxLength: 50,
          // the server trims surrounding spaces and accepts only ASCII letters, digits and . _ -
          pattern: '\\s*[A-Za-z0-9._\\-]+\\s*',
          title: 'Letters, digits, dot, underscore or dash only (no spaces)',
          autoComplete: 'username',
        },
        { name: 'email', label: 'Email', type: 'email', autoComplete: 'email' },
        { name: 'password', label: 'Password (8-72 characters)', type: 'password', minLength: 8, maxLength: 72, autoComplete: 'new-password' },
      ]}
      footer={<>Already registered? <Link to="/login">Log in</Link></>}
    />
  );
}
