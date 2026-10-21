import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AmountForm } from '../src/components/AmountForm.jsx';
import { authedHandlers, mockFetch, renderApp, res } from './helpers.jsx';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('AmountForm (unit)', () => {
  const setup = (action = vi.fn().mockResolvedValue({ id: 9 }), props = {}) => {
    const onDone = vi.fn();
    render(<AmountForm title="Deposit" submitText="Go" action={action} onDone={onDone} {...props} />);
    return { action, onDone, user: userEvent.setup(), input: screen.getByLabelText('Amount') };
  };

  it('submits the typed amount with a fresh UUID idempotency key, then clears and reports success', async () => {
    const { action, onDone, user, input } = setup();
    await user.type(input, '12.5');
    await user.click(screen.getByRole('button', { name: 'Go' }));
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
    expect(action.mock.calls[0][0]).toBe('12.5');
    expect(action.mock.calls[0][1]).toMatch(UUID);
    expect(await screen.findByRole('status')).toHaveTextContent('Done (transaction #9).');
    expect(input).toHaveValue(null);
    expect(onDone).toHaveBeenCalledWith({ id: 9 });
  });

  it('uses a different idempotency key for each submit', async () => {
    const { action, user, input } = setup();
    for (let i = 0; i < 2; i++) {
      await user.type(input, '1');
      await user.click(screen.getByRole('button', { name: 'Go' }));
      await screen.findByRole('status');
    }
    expect(action.mock.calls[0][1]).not.toBe(action.mock.calls[1][1]);
  });

  it.each([
    ['empty', ''],
    ['zero', '0'],
    ['negative', '-5'],
    ['five decimals', '1.23456'],
    ['below minimum', '0.00001'],
  ])('blocks submit for %s amount', async (_n, value) => {
    const { action, user, input } = setup();
    if (value) await user.type(input, value);
    await user.click(screen.getByRole('button', { name: 'Go' }));
    expect(action).not.toHaveBeenCalled();
  });

  it.each(['0.0001', '1.2345', '100', '999999.99'])('accepts valid amount %s', async (value) => {
    const { action, user, input } = setup();
    await user.type(input, value);
    await user.click(screen.getByRole('button', { name: 'Go' }));
    await waitFor(() => expect(action).toHaveBeenCalledTimes(1));
  });

  it('shows the error from a failed action, keeps the amount and re-enables the button', async () => {
    const { user, input } = setup(vi.fn().mockRejectedValue(new Error('Insufficient funds')));
    await user.type(input, '50');
    await user.click(screen.getByRole('button', { name: 'Go' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient funds');
    expect(input).toHaveValue(50);
    expect(screen.getByRole('button', { name: 'Go' })).toBeEnabled();
  });

  it('clears the previous error on the next submit', async () => {
    const action = vi.fn().mockRejectedValueOnce(new Error('bad')).mockResolvedValueOnce({ id: 1 });
    const { user, input } = setup(action);
    await user.type(input, '5');
    await user.click(screen.getByRole('button', { name: 'Go' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Go' }));
    await screen.findByRole('status');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('prevents double submit while the request is pending', async () => {
    let finish;
    const action = vi.fn(() => new Promise((r) => (finish = () => r({ id: 1 }))));
    const { user, input } = setup(action);
    await user.type(input, '5');
    const btn = screen.getByRole('button', { name: 'Go' });
    await user.click(btn);
    expect(screen.getByRole('button', { name: 'Please wait...' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Please wait...' }));
    await user.dblClick(screen.getByRole('button', { name: 'Please wait...' }));
    expect(action).toHaveBeenCalledTimes(1);
    finish();
    await screen.findByRole('status');
    expect(screen.getByRole('button', { name: 'Go' })).toBeEnabled();
  });

  it('renders extra fields inside the form', () => {
    setup(undefined, { extraFields: <input aria-label="extra" /> });
    expect(screen.getByLabelText('extra')).toBeInTheDocument();
  });
});

describe('Dashboard withdraw (no self-service deposit)', () => {
  const section = (name) => screen.getByRole('heading', { name, level: 2 }).closest('section');

  it('shows the balance, wallet id and recent transactions', async () => {
    mockFetch({
      ...authedHandlers(),
      'GET /api/transactions': res(200, {
        content: [{ id: 1, type: 'DEPOSIT', amount: '10', status: 'COMPLETED', createdAt: '2026-01-01T00:00:00', receiverWalletId: 7 }],
        page: 0, totalPages: 1, totalElements: 1,
      }),
    });
    renderApp('/', { token: 't' });
    expect(await screen.findByText(/100[.,]50 USD/)).toBeInTheDocument();
    expect(screen.getByText('Wallet ID 7')).toBeInTheDocument();
    expect(await screen.findByText('deposit')).toBeInTheDocument();
    expect(screen.getByText(/\+10[.,]00 USD/)).toBeInTheDocument();
  });

  it('requests only the 5 latest transactions', async () => {
    const f = mockFetch(authedHandlers());
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    await waitFor(() => expect(f.callsTo('GET /api/transactions').length).toBe(1));
    const url = new URL(f.callsTo('GET /api/transactions')[0].url, 'http://x');
    expect(url.searchParams.get('size')).toBe('5');
    expect(url.searchParams.get('sort')).toBe('createdAt,desc');
  });

  it('has no deposit form: only the withdraw form is offered', async () => {
    mockFetch(authedHandlers());
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(screen.queryByRole('heading', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Withdraw', level: 2 })).toBeInTheDocument();
  });

  it('a regular user has no way to reach the old self-deposit endpoint from the UI', async () => {
    const f = mockFetch(authedHandlers());
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(f.callsTo('POST /api/wallets/me/deposit')).toHaveLength(0);
  });

  it('withdraw posts the amount with an Idempotency-Key, then refreshes wallet and history', async () => {
    const f = mockFetch({
      ...authedHandlers(),
      'POST /api/wallets/me/withdraw': res(200, { id: 55, amount: '25' }),
    });
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    const user = userEvent.setup();
    await user.type(within(section('Withdraw')).getByLabelText('Amount'), '25');
    await user.click(within(section('Withdraw')).getByRole('button', { name: 'Withdraw' }));
    expect(await within(section('Withdraw')).findByRole('status')).toHaveTextContent('transaction #55');
    const call = f.callsTo('POST /api/wallets/me/withdraw')[0];
    expect(call.body).toEqual({ amount: '25' });
    expect(call.init.headers['Idempotency-Key']).toMatch(UUID);
    expect(call.init.headers.Authorization).toBe('Bearer t');
    await waitFor(() => expect(f.callsTo('GET /api/wallets/me').length).toBe(2));
    await waitFor(() => expect(f.callsTo('GET /api/transactions').length).toBe(2));
  });

  it('withdraw with insufficient funds (422) shows the server message and does not refresh', async () => {
    const f = mockFetch({
      ...authedHandlers(),
      'POST /api/wallets/me/withdraw': res(422, { status: 422, error: 'Insufficient funds' }),
    });
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    const user = userEvent.setup();
    await user.type(within(section('Withdraw')).getByLabelText('Amount'), '9999');
    await user.click(within(section('Withdraw')).getByRole('button', { name: 'Withdraw' }));
    expect(await within(section('Withdraw')).findByRole('alert')).toHaveTextContent('Insufficient funds');
    expect(f.callsTo('GET /api/wallets/me')).toHaveLength(1);
  });

  it('a double click on Withdraw sends one request', async () => {
    let finish;
    const f = mockFetch({
      ...authedHandlers(),
      'POST /api/wallets/me/withdraw': () => new Promise((r) => (finish = () => r(res(200, { id: 3 })))),
    });
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    const user = userEvent.setup();
    const sec = section('Withdraw');
    await user.type(within(sec).getByLabelText('Amount'), '5');
    await user.dblClick(within(sec).getByRole('button', { name: 'Withdraw' }));
    expect(f.callsTo('POST /api/wallets/me/withdraw')).toHaveLength(1);
    finish();
    await within(sec).findByRole('status');
  });

  it('a 401 during a withdrawal signs the user out', async () => {
    mockFetch({ ...authedHandlers(), 'POST /api/wallets/me/withdraw': res(401) });
    renderApp('/', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    const user = userEvent.setup();
    await user.type(within(section('Withdraw')).getByLabelText('Amount'), '5');
    await user.click(within(section('Withdraw')).getByRole('button', { name: 'Withdraw' }));
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });

  it('shows a recent-transactions error without breaking the page', async () => {
    mockFetch({ ...authedHandlers(), 'GET /api/transactions': res(500, { error: 'db down' }) });
    renderApp('/', { token: 't' });
    expect(await screen.findByText('db down')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
});

describe('Transfer page', () => {
  async function open(extra = {}) {
    const f = mockFetch({ ...authedHandlers(), ...extra });
    renderApp('/transfer', { token: 't' });
    await screen.findByRole('heading', { name: 'Transfer' });
    return { f, user: userEvent.setup() };
  }
  const receiver = () => screen.getByLabelText(/^Receiver/);
  const amount = () => screen.getByLabelText('Amount');
  const send = () => screen.getByRole('button', { name: 'Send' });

  it('shows available balance', async () => {
    await open();
    expect(screen.getByText(/Available: 100[.,]50 USD/)).toBeInTheDocument();
  });

  it('sends by username with trimmed fields, note, Idempotency-Key, then refreshes wallet', async () => {
    const { f, user } = await open({ 'POST /api/transactions/transfer': res(200, { id: 12, amount: '5' }) });
    await user.type(receiver(), '  bob ');
    await user.type(amount(), '5');
    await user.type(screen.getByLabelText(/^Note/), '  lunch ');
    await user.click(send());
    expect(await screen.findByRole('status')).toHaveTextContent(/Sent 5[.,]00 USD \(transaction #12\)/);
    const call = f.callsTo('POST /api/transactions/transfer')[0];
    expect(call.body).toEqual({ amount: '5', receiverUsername: 'bob', note: 'lunch' });
    expect(call.init.headers['Idempotency-Key']).toMatch(UUID);
    await waitFor(() => expect(f.callsTo('GET /api/wallets/me').length).toBe(2));
    // form cleared
    expect(receiver()).toHaveValue('');
    expect(amount()).toHaveValue(null);
  });

  it('sends by wallet id as a number and omits an empty/blank note', async () => {
    const { f, user } = await open({ 'POST /api/transactions/transfer': res(200, { id: 1, amount: '2' }) });
    await user.selectOptions(screen.getByLabelText('Send to'), 'walletId');
    await user.type(receiver(), '42');
    await user.type(amount(), '2');
    await user.type(screen.getByLabelText(/^Note/), '   ');
    await user.click(send());
    await screen.findByRole('status');
    expect(f.callsTo('POST /api/transactions/transfer')[0].body).toEqual({ amount: '2', receiverWalletId: 42 });
  });

  it('switching mode clears the receiver field', async () => {
    const { user } = await open();
    await user.type(receiver(), 'bob');
    await user.selectOptions(screen.getByLabelText('Send to'), 'walletId');
    expect(receiver()).toHaveValue(null);
  });

  it.each([
    ['empty', ''],
    ['zero', '0'],
    ['negative', '-1'],
    ['too many decimals', '1.00001'],
  ])('blocks %s amount', async (_n, value) => {
    const { f, user } = await open();
    await user.type(receiver(), 'bob');
    if (value) await user.type(amount(), value);
    await user.click(send());
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(0);
  });

  it('blocks a missing receiver', async () => {
    const { f, user } = await open();
    await user.type(amount(), '1');
    await user.click(send());
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(0);
  });

  it('blocks a non-positive or fractional wallet id', async () => {
    const { f, user } = await open();
    await user.selectOptions(screen.getByLabelText('Send to'), 'walletId');
    await user.type(amount(), '1');
    for (const bad of ['0', '-3', '1.5']) {
      await user.clear(receiver());
      await user.type(receiver(), bad);
      await user.click(send());
    }
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(0);
  });

  it('limits the note to 255 characters and shows a live counter', async () => {
    const { user } = await open();
    const note = screen.getByLabelText(/^Note/);
    await user.click(note);
    await user.paste('x'.repeat(300));
    expect(note).toHaveValue('x'.repeat(255));
    expect(screen.getByText('Note (optional, 255/255)')).toBeInTheDocument();
  });

  it('a 255-char note is sent in full', async () => {
    const { f, user } = await open({ 'POST /api/transactions/transfer': res(200, { id: 1, amount: '1' }) });
    await user.type(receiver(), 'bob');
    await user.type(amount(), '1');
    await user.click(screen.getByLabelText(/^Note/));
    await user.paste('n'.repeat(255));
    await user.click(send());
    await screen.findByRole('status');
    expect(f.callsTo('POST /api/transactions/transfer')[0].body.note).toHaveLength(255);
  });

  it('shows the server error for a self-transfer (400)', async () => {
    const { f, user } = await open({
      'POST /api/transactions/transfer': res(400, { status: 400, error: 'Cannot transfer to yourself' }),
    });
    await user.type(receiver(), 'alice');
    await user.type(amount(), '1');
    await user.click(send());
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot transfer to yourself');
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(1);
    // inputs are kept so the user can correct them
    expect(receiver()).toHaveValue('alice');
  });

  it('shows "Insufficient funds" (422) and does not refresh the wallet', async () => {
    const { f, user } = await open({
      'POST /api/transactions/transfer': res(422, { status: 422, error: 'Insufficient funds' }),
    });
    await user.type(receiver(), 'bob');
    await user.type(amount(), '1000');
    await user.click(send());
    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient funds');
    expect(f.callsTo('GET /api/wallets/me')).toHaveLength(1);
  });

  it.each([
    [404, 'Receiver not found'],
    [403, 'Not allowed'],
    [409, 'Idempotency key already used'],
  ])('shows server error %i', async (status, error) => {
    const { user } = await open({ 'POST /api/transactions/transfer': res(status, { status, error }) });
    await user.type(receiver(), 'bob');
    await user.type(amount(), '1');
    await user.click(send());
    expect(await screen.findByRole('alert')).toHaveTextContent(error);
  });

  it('prevents double submit and sends one request with one key', async () => {
    let finish;
    const { f, user } = await open({
      'POST /api/transactions/transfer': () => new Promise((r) => (finish = () => r(res(200, { id: 1, amount: '1' })))),
    });
    await user.type(receiver(), 'bob');
    await user.type(amount(), '1');
    await user.dblClick(send());
    await user.keyboard('{Enter}');
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Please wait...' })).toBeDisabled();
    finish();
    await screen.findByRole('status');
  });

  it('a receiver that is only whitespace passes client validation and is sent as an empty username', async () => {
    const { f, user } = await open({ 'POST /api/transactions/transfer': res(400, { error: 'Receiver required' }) });
    await user.type(receiver(), '   ');
    await user.type(amount(), '1');
    await user.click(send());
    // Expected: blocked on the client (no request). Actual: request sent with receiverUsername ''.
    expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(0);
  });

  it('uses a new Idempotency-Key for each separate transfer', async () => {
    const { f, user } = await open({ 'POST /api/transactions/transfer': res(200, { id: 1, amount: '1' }) });
    for (let i = 0; i < 2; i++) {
      await user.type(receiver(), 'bob');
      await user.type(amount(), '1');
      await user.click(send());
      await waitFor(() => expect(f.callsTo('POST /api/transactions/transfer')).toHaveLength(i + 1));
      await waitFor(() => expect(send()).toBeEnabled());
      await waitFor(() => expect(receiver()).toHaveValue(''));
    }
    const keys = f.callsTo('POST /api/transactions/transfer').map((c) => c.init.headers['Idempotency-Key']);
    expect(new Set(keys).size).toBe(2);
  });
});

