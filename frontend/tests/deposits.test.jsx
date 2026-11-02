import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ADMIN, authedHandlers, mockFetch, page, renderApp, res } from './helpers.jsx';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const req = (id, status, extra = {}) => ({
  id, walletId: 7, amount: '25.0000', note: `ref-${id}`, status,
  createdAt: '2026-01-02T03:04:05', decidedAt: null, decisionNote: null, transactionId: null, ...extra,
});
const adminReq = (id, status, extra = {}) => ({ ...req(id, status), userId: 2, username: 'alice', decidedBy: null, ...extra });

describe('Deposits page (user)', () => {
  const open = async (handlers = {}) => {
    const f = mockFetch({ ...authedHandlers(), 'GET /api/deposit-requests': res(200, page()), ...handlers });
    renderApp('/deposits', { token: 't' });
    await screen.findByRole('heading', { name: 'Deposits' });
    return { f, user: userEvent.setup() };
  };
  const form = () => screen.getByRole('heading', { name: 'Request a deposit' }).closest('section');

  it('is reachable from the nav and explains that an admin must approve', async () => {
    mockFetch({ ...authedHandlers(), 'GET /api/deposit-requests': res(200, page()) });
    renderApp('/', { token: 't' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Deposits' }));
    expect(await screen.findByRole('heading', { name: 'Request a deposit' })).toBeInTheDocument();
    expect(screen.getByText(/admin will approve/)).toBeInTheDocument();
  });

  it('the dashboard links to the deposits page', async () => {
    mockFetch({ ...authedHandlers(), 'GET /api/deposit-requests': res(200, page()) });
    renderApp('/', { token: 't' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Request a deposit' }));
    expect(await screen.findByRole('heading', { name: 'Deposits' })).toBeInTheDocument();
  });

  it('posts amount and trimmed note with an Idempotency-Key, clears the form and reloads the list', async () => {
    const { f, user } = await open({ 'POST /api/deposit-requests': res(201, req(5, 'PENDING')) });
    await user.type(within(form()).getByLabelText('Amount'), '25');
    await user.type(within(form()).getByLabelText(/Note/), '  bank ref 9  ');
    await user.click(within(form()).getByRole('button', { name: 'Send request' }));
    expect(await within(form()).findByRole('status')).toHaveTextContent('Request #5 sent');
    const call = f.callsTo('POST /api/deposit-requests')[0];
    expect(call.body).toEqual({ amount: '25', note: 'bank ref 9' });
    expect(call.init.headers['Idempotency-Key']).toMatch(UUID);
    expect(within(form()).getByLabelText('Amount')).toHaveValue(null);
    await waitFor(() => expect(f.callsTo('GET /api/deposit-requests').length).toBe(2));
  });

  it('omits an empty note and uses a new key for each submit', async () => {
    const { f, user } = await open({ 'POST /api/deposit-requests': res(201, req(5, 'PENDING')) });
    for (let i = 0; i < 2; i++) {
      await user.type(within(form()).getByLabelText('Amount'), '3');
      await user.click(within(form()).getByRole('button', { name: 'Send request' }));
      await waitFor(() => expect(f.callsTo('POST /api/deposit-requests').length).toBe(i + 1));
      await waitFor(() => expect(within(form()).getByRole('button', { name: 'Send request' })).toBeEnabled());
    }
    const [a, b] = f.callsTo('POST /api/deposit-requests');
    expect(a.body).toEqual({ amount: '3' });
    expect(a.init.headers['Idempotency-Key']).not.toBe(b.init.headers['Idempotency-Key']);
  });

  it.each([['empty', ''], ['zero', '0'], ['negative', '-5'], ['five decimals', '1.23456']])(
    'blocks submit for %s amount',
    async (_n, value) => {
      const { f, user } = await open();
      if (value) await user.type(within(form()).getByLabelText('Amount'), value);
      await user.click(within(form()).getByRole('button', { name: 'Send request' }));
      expect(f.callsTo('POST /api/deposit-requests')).toHaveLength(0);
    }
  );

  it('limits the note to 255 characters', async () => {
    await open();
    expect(within(form()).getByLabelText(/Note/)).toHaveAttribute('maxlength', '255');
  });

  it('prevents double submit (double click and Enter) while pending', async () => {
    let finish;
    const { f, user } = await open({
      'POST /api/deposit-requests': () => new Promise((r) => (finish = () => r(res(201, req(5, 'PENDING'))))),
    });
    await user.type(within(form()).getByLabelText('Amount'), '5');
    await user.dblClick(within(form()).getByRole('button', { name: 'Send request' }));
    await user.type(within(form()).getByLabelText('Amount'), '{Enter}');
    expect(f.callsTo('POST /api/deposit-requests')).toHaveLength(1);
    finish();
    await within(form()).findByRole('status');
    expect(within(form()).getByRole('button', { name: 'Send request' })).toBeEnabled();
  });

  it('shows the pending-cap error (409) and keeps the entered values', async () => {
    const { user } = await open({
      'POST /api/deposit-requests': res(409, { status: 409, error: 'Too many pending deposit requests (maximum 5), wait for a decision' }),
    });
    await user.type(within(form()).getByLabelText('Amount'), '5');
    await user.click(within(form()).getByRole('button', { name: 'Send request' }));
    expect(await within(form()).findByRole('alert')).toHaveTextContent('Too many pending');
    expect(within(form()).getByLabelText('Amount')).toHaveValue(5);
    expect(within(form()).getByRole('button', { name: 'Send request' })).toBeEnabled();
  });

  it('lists my requests with status badges, dates, decision notes and the loaded page parameters', async () => {
    const { f } = await open({
      'GET /api/deposit-requests': res(200, page([
        req(1, 'PENDING'),
        req(2, 'APPROVED', { decidedAt: '2026-01-03T00:00:00', decisionNote: 'received', transactionId: 9 }),
        req(3, 'REJECTED', { decidedAt: '2026-01-03T00:00:00', decisionNote: 'no payment found' }),
      ])),
    });
    expect(await screen.findByText('PENDING')).toHaveClass('tag-pending');
    expect(screen.getByText('APPROVED')).toHaveClass('tag-approved');
    expect(screen.getByText('REJECTED')).toHaveClass('tag-rejected');
    expect(screen.getByText('received')).toBeInTheDocument();
    expect(screen.getByText('no payment found')).toBeInTheDocument();
    expect(screen.getByText('ref-1')).toBeInTheDocument();
    expect(screen.getAllByText(/25[.,]00 USD/)).toHaveLength(3);
    const url = new URL(f.callsTo('GET /api/deposit-requests')[0].url, 'http://x');
    expect(url.searchParams.get('size')).toBe('10');
    expect(url.searchParams.get('sort')).toBe('createdAt,desc');
    expect(url.searchParams.has('status')).toBe(false);
  });

  it('shows the empty state and the list error', async () => {
    await open();
    expect(await screen.findByText('You have no deposit requests.')).toBeInTheDocument();
  });

  it('shows a server error for the list', async () => {
    mockFetch({ ...authedHandlers(), 'GET /api/deposit-requests': res(500, { error: 'boom' }) });
    renderApp('/deposits', { token: 't' });
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });

  it('filters by status and goes back to page 0', async () => {
    const { f, user } = await open();
    await user.selectOptions(screen.getByLabelText('Status'), 'APPROVED');
    await waitFor(() => {
      const urls = f.callsTo('GET /api/deposit-requests').map((c) => new URL(c.url, 'http://x'));
      expect(urls.some((u) => u.searchParams.get('status') === 'APPROVED' && u.searchParams.get('page') === '0')).toBe(true);
    });
  });

  it('pages through the requests', async () => {
    const f = mockFetch({
      ...authedHandlers(),
      'GET /api/deposit-requests': (url) => {
        const p = new URL(url, 'http://x').searchParams.get('page');
        return res(200, { content: [req(p === '1' ? 11 : 1, 'PENDING')], page: Number(p), size: 10, totalElements: 12, totalPages: 2 });
      },
    });
    renderApp('/deposits', { token: 't' });
    const user = userEvent.setup();
    await screen.findByText('Page 1 of 2 (12 total)');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 2 (12 total)');
    expect(screen.getByText('11')).toBeInTheDocument();
    expect(f.callsTo('GET /api/deposit-requests').length).toBeGreaterThanOrEqual(2);
  });

  it('refreshes the wallet when the page opens so an approval shows up in the balance', async () => {
    const { f } = await open();
    await waitFor(() => expect(f.callsTo('GET /api/wallets/me').length).toBe(2));
  });
});

describe('Admin deposit requests page', () => {
  const adminHandlers = (extra = {}) => ({
    ...authedHandlers(ADMIN),
    'GET /api/admin/deposit-requests': res(200, page([adminReq(1, 'PENDING'), adminReq(2, 'PENDING', { username: 'bob', note: null })])),
    ...extra,
  });
  const open = async (extra = {}) => {
    const f = mockFetch(adminHandlers(extra));
    renderApp('/admin/deposit-requests', { token: 't' });
    await screen.findByRole('heading', { name: 'Deposit requests' });
    return { f, user: userEvent.setup() };
  };
  const rowOf = (text) => screen.getByText(text).closest('tr');

  it('is reachable from the admin sub navigation', async () => {
    mockFetch(adminHandlers({ 'GET /api/admin/users': res(200, page()) }));
    renderApp('/admin', { token: 't' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Deposit requests' }));
    expect(await screen.findByRole('heading', { name: 'Deposit requests', level: 2 })).toBeInTheDocument();
  });

  it('lists PENDING requests by default with user, wallet, amount, note and date', async () => {
    const { f } = await open();
    expect(await screen.findByText('bob')).toBeInTheDocument();
    expect(screen.getByText('ref-1')).toBeInTheDocument();
    expect(screen.getAllByText('PENDING', { selector: 'span' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(2);
    const url = new URL(f.callsTo('GET /api/admin/deposit-requests')[0].url, 'http://x');
    expect(url.searchParams.get('status')).toBe('PENDING');
    expect(screen.getByLabelText('Status')).toHaveValue('PENDING');
  });

  it('the status filter reloads, and decided requests have no action buttons', async () => {
    const { f, user } = await open({
      'GET /api/admin/deposit-requests': (url) =>
        res(200, page(new URL(url, 'http://x').searchParams.get('status') === 'APPROVED'
          ? [adminReq(3, 'APPROVED', { decisionNote: 'done' })]
          : [adminReq(1, 'PENDING')])),
    });
    await screen.findByText('ref-1');
    await user.selectOptions(screen.getByLabelText('Status'), 'APPROVED');
    expect(await screen.findByText('APPROVED', { selector: 'span' })).toHaveClass('tag-approved');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(f.callsTo('GET /api/admin/deposit-requests').length).toBeGreaterThanOrEqual(2);
  });

  it('shows the empty state and a list error', async () => {
    await open({ 'GET /api/admin/deposit-requests': res(200, page()) });
    expect(await screen.findByText('No deposit requests.')).toBeInTheDocument();
  });

  it('shows a server error for the list', async () => {
    mockFetch(adminHandlers({ 'GET /api/admin/deposit-requests': res(500, { error: 'db down' }) }));
    renderApp('/admin/deposit-requests', { token: 't' });
    expect(await screen.findByRole('alert')).toHaveTextContent('db down');
  });

  it('approve posts to the approve endpoint, disables buttons while pending, then reloads', async () => {
    let finish;
    const { f, user } = await open({
      'POST /api/admin/deposit-requests/1/approve': () => new Promise((r) => (finish = () => r(res(200, adminReq(1, 'APPROVED'))))),
    });
    await screen.findByText('ref-1');
    await user.click(within(rowOf('ref-1')).getByRole('button', { name: 'Approve' }));
    await user.dblClick(within(rowOf('ref-1')).getByRole('button', { name: 'Approve' }));
    screen.getAllByRole('button', { name: /Approve|Reject/ }).forEach((b) => expect(b).toBeDisabled());
    expect(f.callsTo('POST /api/admin/deposit-requests/1/approve')).toHaveLength(1);
    finish();
    expect(await screen.findByRole('status')).toHaveTextContent('Request #1 approved.');
    await waitFor(() => expect(f.callsTo('GET /api/admin/deposit-requests').length).toBe(2));
    expect(f.callsTo('POST /api/admin/deposit-requests/1/approve')[0].body).toEqual({});
  });

  it('shows the server error when the request was already decided and reloads the list', async () => {
    const { f, user } = await open({
      'POST /api/admin/deposit-requests/1/approve': res(409, { status: 409, error: 'Deposit request already APPROVED' }),
    });
    await screen.findByText('ref-1');
    await user.click(within(rowOf('ref-1')).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Deposit request already APPROVED');
    await waitFor(() => expect(f.callsTo('GET /api/admin/deposit-requests').length).toBe(2));
    expect(screen.queryByText(/approved\./)).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Approve' })[0]).toBeEnabled();
  });

  it('reject asks for a reason, requires it and sends it', async () => {
    const { f, user } = await open({
      'POST /api/admin/deposit-requests/2/reject': res(200, adminReq(2, 'REJECTED')),
    });
    await screen.findByText('bob');
    await user.click(within(rowOf('bob')).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByLabelText(/Reason for rejecting request #2/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm reject' })); // empty reason is blocked
    expect(f.callsTo('POST /api/admin/deposit-requests/2/reject')).toHaveLength(0);

    await user.type(screen.getByLabelText(/Reason for rejecting/), '  no payment received ');
    await user.click(screen.getByRole('button', { name: 'Confirm reject' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Request #2 rejected.');
    expect(f.callsTo('POST /api/admin/deposit-requests/2/reject')[0].body).toEqual({ decisionNote: 'no payment received' });
    await waitFor(() => expect(f.callsTo('GET /api/admin/deposit-requests').length).toBe(2));
    expect(screen.queryByLabelText(/Reason for rejecting/)).not.toBeInTheDocument();
  });

  it('reject can be cancelled without a request', async () => {
    const { f, user } = await open();
    await screen.findByText('bob');
    await user.click(within(rowOf('bob')).getByRole('button', { name: 'Reject' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText(/Reason for rejecting/)).not.toBeInTheDocument();
    expect(f.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('a failed reject keeps the reason and shows the error', async () => {
    const { user } = await open({
      'POST /api/admin/deposit-requests/1/reject': res(409, { error: 'Deposit request already REJECTED' }),
    });
    await screen.findByText('ref-1');
    await user.click(within(rowOf('ref-1')).getByRole('button', { name: 'Reject' }));
    await user.type(screen.getByLabelText(/Reason for rejecting/), 'late');
    await user.click(screen.getByRole('button', { name: 'Confirm reject' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already REJECTED');
    expect(screen.getByLabelText(/Reason for rejecting/)).toHaveValue('late');
  });
});

describe('Route protection for deposit pages', () => {
  it('a USER is redirected away from /admin/deposit-requests and sees no admin link or call', async () => {
    const f = mockFetch({ ...authedHandlers(), 'GET /api/deposit-requests': res(200, page()) });
    renderApp('/admin/deposit-requests', { token: 't' });
    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(screen.queryByRole('heading', { name: 'Deposit requests' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(f.callsTo('GET /api/admin/deposit-requests')).toHaveLength(0);
  });

  it('an anonymous visitor of /deposits is sent to the login page', async () => {
    const f = mockFetch({});
    renderApp('/deposits');
    expect(await screen.findByRole('heading', { name: /sign in|log in|login/i })).toBeInTheDocument();
    expect(f.callsTo('GET /api/deposit-requests')).toHaveLength(0);
  });

  it('an admin sees the admin link', async () => {
    mockFetch({ ...authedHandlers(ADMIN), 'GET /api/deposit-requests': res(200, page()) });
    renderApp('/deposits', { token: 't' });
    expect(await screen.findByRole('link', { name: 'Admin' })).toBeInTheDocument();
  });
});
