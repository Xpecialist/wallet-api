import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { getToken } from '../src/api/client.js';
import { ADMIN, USER, authedHandlers, mockFetch, renderApp, res } from './helpers.jsx';

describe('login', () => {
  it('logs in, stores the token, loads profile and shows the dashboard', async () => {
    const f = mockFetch({
      'POST /api/auth/login': res(200, { token: 'jwt-1' }),
      ...authedHandlers(),
    });
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(f.callsTo('POST /api/auth/login')[0].body).toEqual({ username: 'alice', password: 'secret123' });
    expect(getToken()).toBe('jwt-1');
    expect(sessionStorage.getItem('wallet.token')).toBe('jwt-1');
    expect(f.callsTo('GET /api/users/me')[0].init.headers.Authorization).toBe('Bearer jwt-1');
    expect(screen.getByText('alice')).toBeInTheDocument(); // navbar
  });

  it('shows the server message on bad credentials and stays on the login page', async () => {
    mockFetch({ 'POST /api/auth/login': res(401, { status: 401, error: 'Invalid username or password' }) });
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
    expect(getToken()).toBeNull();
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
  });

  it('shows "Cannot reach the server" when the network is down', async () => {
    globalThis.fetch = () => Promise.reject(new TypeError('Failed to fetch'));
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server');
  });

  it('does not submit with empty fields', async () => {
    const f = mockFetch({});
    renderApp('/login');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Log in' }));
    expect(f).not.toHaveBeenCalled();
  });

  it('disables the button while the request is in flight', async () => {
    let release;
    mockFetch({ 'POST /api/auth/login': () => new Promise((r) => (release = () => r(res(401, { error: 'no' })))) });
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'a');
    await user.type(screen.getByLabelText('Password'), 'b');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(screen.getByRole('button', { name: 'Please wait...' })).toBeDisabled();
    release();
    expect(await screen.findByRole('alert')).toHaveTextContent('no');
  });

  it('links to the register page', async () => {
    renderApp('/login');
    await userEvent.setup().click(screen.getByRole('link', { name: 'Register' }));
    expect(screen.getByRole('heading', { name: 'Create account' })).toBeInTheDocument();
  });
});

describe('register', () => {
  it('registers, signs in and shows the dashboard', async () => {
    const f = mockFetch({
      'POST /api/auth/register': res(201, { token: 'jwt-reg' }),
      ...authedHandlers(),
    });
    renderApp('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Username/), 'newuser');
    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'longenough1');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(f.callsTo('POST /api/auth/register')[0].body).toEqual({
      username: 'newuser',
      email: 'new@example.com',
      password: 'longenough1',
    });
    expect(getToken()).toBe('jwt-reg');
  });

  it('blocks submit for a bad email', async () => {
    const f = mockFetch({});
    renderApp('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Username/), 'abc');
    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.type(screen.getByLabelText(/^Password/), 'longenough1');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(f).not.toHaveBeenCalled();
  });

  it('declares the length rules (jsdom does not enforce minlength, so the attributes are checked)', () => {
    renderApp('/register');
    expect(screen.getByLabelText(/^Username/)).toHaveAttribute('minlength', '3');
    expect(screen.getByLabelText(/^Username/)).toHaveAttribute('maxlength', '50');
    expect(screen.getByLabelText(/^Password/)).toHaveAttribute('minlength', '8');
    expect(screen.getByLabelText(/^Password/)).toHaveAttribute('maxlength', '72');
  });

  it('caps username at 50 and password at 72 characters', async () => {
    mockFetch({});
    renderApp('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Username/), 'u'.repeat(60));
    await user.type(screen.getByLabelText(/^Password/), 'p'.repeat(80));
    expect(screen.getByLabelText(/^Username/)).toHaveValue('u'.repeat(50));
    expect(screen.getByLabelText(/^Password/)).toHaveValue('p'.repeat(72));
  });

  it('shows a 409 duplicate-username error', async () => {
    mockFetch({ 'POST /api/auth/register': res(409, { status: 409, error: 'Username already taken' }) });
    renderApp('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Username/), 'alice');
    await user.type(screen.getByLabelText('Email'), 'a@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'longenough1');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Username already taken');
    expect(getToken()).toBeNull();
  });
});

describe('session restore, logout and expiry', () => {
  it('restores a stored session on load without showing the login page', async () => {
    const f = mockFetch(authedHandlers());
    renderApp('/', { token: 'stored' });
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(f.callsTo('GET /api/users/me')[0].init.headers.Authorization).toBe('Bearer stored');
    expect(f.callsTo('GET /api/wallets/me')).toHaveLength(1);
  });

  it('shows a loading state while the profile loads', async () => {
    mockFetch({
      'GET /api/users/me': () => new Promise(() => {}),
      'GET /api/wallets/me': () => new Promise(() => {}),
    });
    renderApp('/', { token: 'stored' });
    expect(screen.getByText('Loading your account...')).toBeInTheDocument();
  });

  it('an expired/invalid stored token (401) clears the session and redirects to login', async () => {
    mockFetch({
      'GET /api/users/me': res(401),
      'GET /api/wallets/me': res(401),
    });
    renderApp('/', { token: 'expired' });
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem('wallet.token')).toBeNull();
  });

  it('a 401 on a later request signs the user out', async () => {
    mockFetch({
      ...authedHandlers(),
      'GET /api/users/me': res(200, USER),
      'GET /api/transactions': res(401),
    });
    renderApp('/', { token: 'soon-expired' });
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('log out clears the token, redirects to login and protects pages again', async () => {
    mockFetch(authedHandlers());
    renderApp('/', { token: 'stored' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Log out' }));
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem('wallet.token')).toBeNull();
  });

  it('a non-401 failure while loading the profile does not hang the app', async () => {
    mockFetch({
      'GET /api/users/me': res(500, { error: 'boom' }),
      'GET /api/wallets/me': res(500, { error: 'boom' }),
      'GET /api/transactions': res(200, { content: [], page: 0, totalPages: 0, totalElements: 0 }),
    });
    renderApp('/', { token: 'stored' });
    expect(await screen.findByText('Could not load your wallet.')).toBeInTheDocument();
  });

  it('logged-in users visiting /login are sent to the dashboard', async () => {
    mockFetch(authedHandlers());
    renderApp('/login', { token: 'stored' });
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
});

describe('route protection', () => {
  it.each(['/', '/transfer', '/history', '/lookup', '/profile', '/admin', '/admin/users', '/admin/deposit'])(
    'unauthenticated %s redirects to login without calling the API',
    async (route) => {
      const f = mockFetch({});
      renderApp(route);
      expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
      expect(f).not.toHaveBeenCalled();
    }
  );

  it('USER does not see the Admin link', async () => {
    mockFetch(authedHandlers(USER));
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });

  it.each(['/admin', '/admin/users', '/admin/transactions', '/admin/deposit'])(
    'USER visiting %s is redirected to the dashboard and no admin API is called',
    async (route) => {
      const f = mockFetch(authedHandlers(USER));
      renderApp(route, { token: 't' });
      expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
      expect(f.calls.some((c) => c.path.startsWith('/api/admin'))).toBe(false);
    }
  );

  it('ADMIN sees the Admin link and /admin lands on the users table', async () => {
    mockFetch({
      ...authedHandlers(ADMIN),
      'GET /api/admin/users': res(200, {
        content: [ADMIN],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      }),
    });
    renderApp('/admin', { token: 't' });
    expect(await screen.findByRole('heading', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All transactions' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Deposit to wallet' })).toBeInTheDocument();
  });

  it('unknown routes redirect to the dashboard', async () => {
    mockFetch(authedHandlers());
    renderApp('/nope/nothing', { token: 't' });
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });

  it('BUG: a failing wallet call (404/500) discards the successfully loaded profile (Promise.all in AuthContext)', async () => {
    mockFetch({
      'GET /api/users/me': res(200, USER),
      'GET /api/wallets/me': res(404, { error: 'No wallet' }),
      'GET /api/transactions': res(200, { content: [], page: 0, totalPages: 0, totalElements: 0 }),
    });
    renderApp('/profile', { token: 't' });
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument());
    expect(getToken()).toBe('t');
  });
});

