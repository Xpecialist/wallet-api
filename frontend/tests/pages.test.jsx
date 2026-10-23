import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { api, getToken } from '../src/api/client.js';
import { ADMIN, USER, WALLET, authedHandlers, mockFetch, page, renderApp, res } from './helpers.jsx';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('Profile page', () => {
  async function open(extra = {}) {
    const f = mockFetch({ ...authedHandlers(), ...extra });
    renderApp('/profile', { token: 't' });
    await screen.findByRole('heading', { name: 'Profile' });
    return { f, user: userEvent.setup() };
  }
  const emailSection = () => screen.getByRole('heading', { name: 'Change email' }).closest('section');
  const pwSection = () => screen.getByRole('heading', { name: 'Change password' }).closest('section');

  it('shows username, email, role and member-since date', async () => {
    await open();
    const dl = document.querySelector('dl');
    expect(within(dl).getByText('alice')).toBeInTheDocument();
    expect(within(dl).getByText('alice@example.com')).toBeInTheDocument();
    expect(within(dl).getByText('USER')).toBeInTheDocument();
    expect(within(dl).getByText(new Date(USER.createdAt).toLocaleString())).toBeInTheDocument();
  });

  it('prefills the email field', async () => {
    await open();
    expect(within(emailSection()).getByLabelText('Email')).toHaveValue('alice@example.com');
  });

  it('changes the email (trimmed), reloads the profile and shows success', async () => {
    let current = USER;
    const { f, user } = await open({
      'GET /api/users/me': () => res(200, current),
      'PATCH /api/users/me': () => {
        current = { ...USER, email: 'new@example.com' };
        return res(200, current);
      },
    });
    const input = within(emailSection()).getByLabelText('Email');
    await user.clear(input);
    await user.type(input, '  new@example.com ');
    await user.click(within(emailSection()).getByRole('button', { name: 'Save email' }));
    expect(await within(emailSection()).findByRole('status')).toHaveTextContent('Email updated.');
    expect(f.callsTo('PATCH /api/users/me')[0].body).toEqual({ email: 'new@example.com' });
    await waitFor(() => expect(f.callsTo('GET /api/users/me').length).toBe(2));
    expect(await screen.findAllByText('new@example.com')).not.toHaveLength(0);
  });

  it('blocks an invalid or empty email', async () => {
    const { f, user } = await open();
    const input = within(emailSection()).getByLabelText('Email');
    await user.clear(input);
    await user.click(within(emailSection()).getByRole('button', { name: 'Save email' }));
    await user.type(input, 'nope');
    await user.click(within(emailSection()).getByRole('button', { name: 'Save email' }));
    expect(f.callsTo('PATCH /api/users/me')).toHaveLength(0);
  });

  it('shows a 409 duplicate email error and no success message', async () => {
    const { user } = await open({ 'PATCH /api/users/me': res(409, { status: 409, error: 'Email already in use' }) });
    const input = within(emailSection()).getByLabelText('Email');
    await user.clear(input);
    await user.type(input, 'taken@example.com');
    await user.click(within(emailSection()).getByRole('button', { name: 'Save email' }));
    expect(await within(emailSection()).findByRole('alert')).toHaveTextContent('Email already in use');
    expect(within(emailSection()).queryByRole('status')).not.toBeInTheDocument();
  });

  it('changes the password, sends both values and clears the fields', async () => {
    const { f, user } = await open({ 'POST /api/users/me/password': res(204) });
    const s = pwSection();
    await user.type(within(s).getByLabelText('Current password'), 'oldpassword');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.click(within(s).getByRole('button', { name: 'Change password' }));
    expect(await within(s).findByRole('status')).toHaveTextContent('Password changed.');
    expect(f.callsTo('POST /api/users/me/password')[0].body).toEqual({
      currentPassword: 'oldpassword',
      newPassword: 'newpassword1',
    });
    expect(within(s).getByLabelText('Current password')).toHaveValue('');
    expect(within(s).getByLabelText(/^New password/)).toHaveValue('');
  });

  it('stores the fresh token returned by a password change so the session survives and later calls use it', async () => {
    const { f, user } = await open({
      'POST /api/users/me/password': res(200, { token: 'fresh-jwt', tokenType: 'Bearer' }),
      'GET /api/wallets/me': res(200, WALLET),
    });
    const s = pwSection();
    await user.type(within(s).getByLabelText('Current password'), 'oldpassword');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.click(within(s).getByRole('button', { name: 'Change password' }));
    expect(await within(s).findByRole('status')).toHaveTextContent('Password changed.');
    expect(f.callsTo('POST /api/users/me/password')[0].init.headers.Authorization).toBe('Bearer t');
    expect(getToken()).toBe('fresh-jwt');
    expect(sessionStorage.getItem('wallet.token')).toBe('fresh-jwt');
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument();
    await api.myWallet();
    expect(f.callsTo('GET /api/wallets/me').at(-1).init.headers.Authorization).toBe('Bearer fresh-jwt');
  });

  it('a failed password change keeps the old token', async () => {
    const { user } = await open({
      'POST /api/users/me/password': res(400, { status: 400, error: 'Current password is incorrect' }),
    });
    const s = pwSection();
    await user.type(within(s).getByLabelText('Current password'), 'wrongpass');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.click(within(s).getByRole('button', { name: 'Change password' }));
    await within(s).findByRole('alert');
    expect(getToken()).toBe('t');
  });

  it('declares the 8-72 character rule on the new password (jsdom does not enforce minlength, so the attributes are checked)', async () => {
    const { user } = await open();
    const input = within(pwSection()).getByLabelText(/^New password/);
    expect(input).toHaveAttribute('minlength', '8');
    expect(input).toHaveAttribute('maxlength', '72');
    expect(input).toBeRequired();
    await user.click(input);
    await user.paste('x'.repeat(80));
    expect(input).toHaveValue('x'.repeat(72));
  });

  it('wrong current password (400) shows the error, keeps the user signed in and keeps the fields', async () => {
    const { user } = await open({
      'POST /api/users/me/password': res(400, { status: 400, error: 'Current password is incorrect' }),
    });
    const s = pwSection();
    await user.type(within(s).getByLabelText('Current password'), 'wrongpass');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.click(within(s).getByRole('button', { name: 'Change password' }));
    expect(await within(s).findByRole('alert')).toHaveTextContent('Current password is incorrect');
    expect(getToken()).toBe('t');
    expect(within(s).getByLabelText('Current password')).toHaveValue('wrongpass');
  });

  it('prevents double submit of the password form', async () => {
    let finish;
    const { f, user } = await open({
      'POST /api/users/me/password': () => new Promise((r) => (finish = () => r(res(204)))),
    });
    const s = pwSection();
    await user.type(within(s).getByLabelText('Current password'), 'oldpassword');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.dblClick(within(s).getByRole('button', { name: 'Change password' }));
    expect(f.callsTo('POST /api/users/me/password')).toHaveLength(1);
    finish();
    await within(s).findByRole('status');
  });
});

describe('Wallet lookup page', () => {
  async function open(extra = {}) {
    const f = mockFetch({ ...authedHandlers(), ...extra });
    renderApp('/lookup', { token: 't' });
    await screen.findByRole('heading', { name: 'Wallet lookup' });
    return { f, user: userEvent.setup() };
  }
  const submit = async (user, id) => {
    const input = screen.getByLabelText('Wallet ID');
    await user.clear(input);
    await user.type(input, id);
    await user.click(screen.getByRole('button', { name: 'Look up' }));
  };

  it('looks up a wallet and shows owner and balance', async () => {
    const { f, user } = await open({
      'GET /api/wallets/9': res(200, { id: 9, userId: 4, username: 'carol', balance: '12.3', currency: 'EUR' }),
    });
    await submit(user, '9');
    expect(await screen.findByText('carol (user #4)')).toBeInTheDocument();
    expect(screen.getByText(/^12[.,]30 EUR$/)).toBeInTheDocument();
    expect(f.callsTo('GET /api/wallets/9')[0].init.headers.Authorization).toBe('Bearer t');
  });

  it.each([
    [403, 'Access denied'],
    [404, 'Wallet not found'],
    [500, 'Internal error'],
  ])('shows the %i error and no result', async (status, error) => {
    const { user } = await open({ 'GET /api/wallets/9': res(status, { status, error }) });
    await submit(user, '9');
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
    expect(screen.queryByText('Owner')).not.toBeInTheDocument();
  });

  it('clears the previous result when the next lookup fails', async () => {
    const { user } = await open({
      'GET /api/wallets/9': res(200, { id: 9, userId: 4, username: 'carol', balance: '1', currency: 'EUR' }),
      'GET /api/wallets/10': res(404, { error: 'Wallet not found' }),
    });
    await submit(user, '9');
    await screen.findByText('carol (user #4)');
    await submit(user, '10');
    await screen.findByRole('alert');
    expect(screen.queryByText('carol (user #4)')).not.toBeInTheDocument();
  });

  it.each(['', '0', '-1', '1.5'])('blocks invalid wallet id "%s"', async (value) => {
    const { f, user } = await open();
    if (value) await user.type(screen.getByLabelText('Wallet ID'), value);
    await user.click(screen.getByRole('button', { name: 'Look up' }));
    expect(f.calls.filter((c) => c.path.startsWith('/api/wallets/') && c.path !== '/api/wallets/me')).toHaveLength(0);
  });

  it('shows "Cannot reach the server" on network failure', async () => {
    const { user } = await open();
    globalThis.fetch = () => Promise.reject(new TypeError('x'));
    await submit(user, '9');
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server');
  });
});

describe('Admin pages', () => {
  const USERS = [
    ADMIN,
    USER,
    { id: 3, username: 'bob', email: 'bob@example.com', role: 'USER', enabled: false, createdAt: '2026-02-01T00:00:00' },
  ];
  const adminHandlers = (extra = {}) => ({
    ...authedHandlers(ADMIN),
    'GET /api/admin/users': res(200, page(USERS, { totalElements: 3 })),
    ...extra,
  });
  const rowOf = (name) => screen.getByText(name, { selector: 'td' }).closest('tr');

  it('lists users with status and requests page 0, size 10, sort id,asc', async () => {
    const f = mockFetch(adminHandlers());
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('bob', { selector: 'td' });
    const sp = new URL(f.callsTo('GET /api/admin/users')[0].url, 'http://x').searchParams;
    expect([sp.get('page'), sp.get('size'), sp.get('sort')]).toEqual(['0', '10', 'id,asc']);
    expect(within(rowOf('bob')).getByText('Disabled')).toBeInTheDocument();
    expect(within(rowOf('alice')).getByText('Enabled')).toBeInTheDocument();
    expect(within(rowOf('bob')).getByRole('button', { name: 'Enable' })).toBeEnabled();
    expect(within(rowOf('alice')).getByRole('button', { name: 'Disable' })).toBeEnabled();
  });

  it('does not let an admin disable their own account (button disabled)', async () => {
    mockFetch(adminHandlers());
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('bob', { selector: 'td' });
    expect(within(rowOf('admin')).getByRole('button', { name: 'Disable' })).toBeDisabled();
  });

  it('disabling a user sends PATCH {enabled:false} and reloads the list', async () => {
    const f = mockFetch(adminHandlers({ 'PATCH /api/admin/users/2/status': res(200, { ...USER, enabled: false }) }));
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('bob', { selector: 'td' });
    await userEvent.setup().click(within(rowOf('alice')).getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(f.callsTo('GET /api/admin/users').length).toBe(2));
    expect(f.callsTo('PATCH /api/admin/users/2/status')[0].body).toEqual({ enabled: false });
  });

  it('enabling a disabled user sends {enabled:true}', async () => {
    const f = mockFetch(adminHandlers({ 'PATCH /api/admin/users/3/status': res(200, {}) }));
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('bob', { selector: 'td' });
    await userEvent.setup().click(within(rowOf('bob')).getByRole('button', { name: 'Enable' }));
    await waitFor(() => expect(f.callsTo('PATCH /api/admin/users/3/status')).toHaveLength(1));
    expect(f.callsTo('PATCH /api/admin/users/3/status')[0].body).toEqual({ enabled: true });
  });

  it('shows the action error when the status change fails and does not reload', async () => {
    const f = mockFetch(adminHandlers({ 'PATCH /api/admin/users/2/status': res(404, { error: 'User not found' }) }));
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('bob', { selector: 'td' });
    await userEvent.setup().click(within(rowOf('alice')).getByRole('button', { name: 'Disable' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('User not found');
    expect(f.callsTo('GET /api/admin/users')).toHaveLength(1);
  });

  it('pages through users', async () => {
    const f = mockFetch(
      adminHandlers({
        'GET /api/admin/users': (url) => {
          const p = Number(new URL(url, 'http://x').searchParams.get('page'));
          return res(200, page(USERS, { page: p, totalPages: 2, totalElements: 13 }));
        },
      })
    );
    renderApp('/admin/users', { token: 't' });
    await screen.findByText('Page 1 of 2 (13 total)');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 2 (13 total)');
    const sp = new URL(f.callsTo('GET /api/admin/users').at(-1).url, 'http://x').searchParams;
    expect(sp.get('page')).toBe('1');
  });

  it('shows the empty state and the error state for users', async () => {
    mockFetch(adminHandlers({ 'GET /api/admin/users': res(200, page([])) }));
    renderApp('/admin/users', { token: 't' });
    expect(await screen.findByText('No users.')).toBeInTheDocument();
  });

  it('shows a server error for the users list', async () => {
    mockFetch(adminHandlers({ 'GET /api/admin/users': res(403, { error: 'Forbidden' }) }));
    renderApp('/admin/users', { token: 't' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Forbidden');
  });

  it('All transactions uses the admin endpoint with filters and no direction column', async () => {
    const f = mockFetch(
      adminHandlers({
        'GET /api/admin/transactions': res(200, page([
          { id: 5, type: 'TRANSFER', senderWalletId: 7, receiverWalletId: 8, amount: '5', status: 'COMPLETED', note: 'hi', createdAt: '2026-01-01T00:00:00' },
        ])),
      })
    );
    renderApp('/admin/transactions', { token: 't' });
    await screen.findByText('hi', { selector: 'td' });
    expect(screen.queryByRole('columnheader', { name: 'Direction' })).not.toBeInTheDocument();
    expect(f.callsTo('GET /api/transactions')).toHaveLength(0);
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('Type'), 'TRANSFER');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(f.callsTo('GET /api/admin/transactions').length).toBe(2));
    const sp = new URL(f.callsTo('GET /api/admin/transactions').at(-1).url, 'http://x').searchParams;
    expect(sp.get('type')).toBe('TRANSFER');
    expect(sp.get('size')).toBe('10');
  });

  describe('Deposit to wallet', () => {
    const open = async (extra = {}) => {
      const f = mockFetch(adminHandlers(extra));
      renderApp('/admin/deposit', { token: 't' });
      await screen.findByRole('heading', { name: 'Deposit to wallet', level: 2 });
      return { f, user: userEvent.setup() };
    };

    it('deposits to the given wallet with an Idempotency-Key', async () => {
      const { f, user } = await open({ 'POST /api/admin/wallets/5/deposit': res(200, { id: 88 }) });
      await user.type(screen.getByLabelText('Wallet ID'), '5');
      await user.type(screen.getByLabelText('Amount'), '20.5');
      await user.click(screen.getByRole('button', { name: 'Deposit' }));
      expect(await screen.findByRole('status')).toHaveTextContent('transaction #88');
      const call = f.callsTo('POST /api/admin/wallets/5/deposit')[0];
      expect(call.body).toEqual({ amount: '20.5' });
      expect(call.init.headers['Idempotency-Key']).toMatch(UUID);
    });

    it.each([
      ['wallet id missing', '', '5'],
      ['wallet id zero', '0', '5'],
      ['amount missing', '5', ''],
      ['amount zero', '5', '0'],
      ['amount negative', '5', '-2'],
      ['amount with 5 decimals', '5', '0.12345'],
    ])('blocks submit when %s', async (_n, wallet, amt) => {
      const { f, user } = await open();
      if (wallet) await user.type(screen.getByLabelText('Wallet ID'), wallet);
      if (amt) await user.type(screen.getByLabelText('Amount'), amt);
      await user.click(screen.getByRole('button', { name: 'Deposit' }));
      expect(f.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    });

    it('shows a 404 for an unknown wallet', async () => {
      const { user } = await open({ 'POST /api/admin/wallets/99/deposit': res(404, { error: 'Wallet not found' }) });
      await user.type(screen.getByLabelText('Wallet ID'), '99');
      await user.type(screen.getByLabelText('Amount'), '1');
      await user.click(screen.getByRole('button', { name: 'Deposit' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Wallet not found');
    });

    it('a 403 (role revoked server-side) is shown as an error and does not sign out', async () => {
      const { user } = await open({ 'POST /api/admin/wallets/5/deposit': res(403, { error: 'Forbidden' }) });
      await user.type(screen.getByLabelText('Wallet ID'), '5');
      await user.type(screen.getByLabelText('Amount'), '1');
      await user.click(screen.getByRole('button', { name: 'Deposit' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Forbidden');
      expect(getToken()).toBe('t');
    });

    it('prevents double submit', async () => {
      let finish;
      const { f, user } = await open({
        'POST /api/admin/wallets/5/deposit': () => new Promise((r) => (finish = () => r(res(200, { id: 1 })))),
      });
      await user.type(screen.getByLabelText('Wallet ID'), '5');
      await user.type(screen.getByLabelText('Amount'), '1');
      await user.dblClick(screen.getByRole('button', { name: 'Deposit' }));
      expect(f.callsTo('POST /api/admin/wallets/5/deposit')).toHaveLength(1);
      finish();
      await screen.findByRole('status');
    });
  });
});
