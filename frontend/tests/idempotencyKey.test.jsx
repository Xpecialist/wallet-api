import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, isIndeterminate } from '../src/api/client.js';
import { AmountForm } from '../src/components/AmountForm.jsx';
import { authedHandlers, mockFetch, page, renderApp, res } from './helpers.jsx';

/** A fetch handler that answers with the given outcomes in order (an Error instance means a network failure). */
const sequence = (...outcomes) => {
  let i = 0;
  return () => {
    const o = outcomes[Math.min(i++, outcomes.length - 1)];
    if (o instanceof Error) throw o;
    return o;
  };
};
const networkDown = () => new TypeError('Failed to fetch');
const keysOf = (f, key) => f.callsTo(key).map((c) => c.init.headers['Idempotency-Key']);

describe('isIndeterminate', () => {
  it.each([0, 502, 503, 504])('treats status %i as an unknown outcome', (status) => {
    expect(isIndeterminate(new ApiError(status, 'x'))).toBe(true);
  });

  it('treats a 500 without a JSON error body as unknown and a 500 with one as definite', () => {
    const bare = new ApiError(500, 'Request failed (500)');
    bare.hasBody = false;
    expect(isIndeterminate(bare)).toBe(true);
    expect(isIndeterminate(new ApiError(500, 'Internal error'))).toBe(false);
  });

  it.each([400, 401, 404, 409, 422, 429])('treats %i as a definite answer', (status) => {
    expect(isIndeterminate(new ApiError(status, 'x'))).toBe(false);
  });

  it('treats a non-ApiError as definite', () => {
    expect(isIndeterminate(new Error('boom'))).toBe(false);
  });
});

describe('Transfer page: Idempotency-Key reuse', () => {
  const URL = 'POST /api/transactions/transfer';
  async function open(handler) {
    const f = mockFetch({ ...authedHandlers(), [URL]: handler });
    renderApp('/transfer', { token: 't' });
    await screen.findByRole('heading', { name: 'Transfer' });
    return { f, user: userEvent.setup() };
  }
  const send = () => screen.getByRole('button', { name: 'Send' });
  async function fill(user, to = 'bob', amount = '5') {
    await user.clear(screen.getByLabelText(/^Receiver/));
    await user.type(screen.getByLabelText(/^Receiver/), to);
    await user.clear(screen.getByLabelText('Amount'));
    await user.type(screen.getByLabelText('Amount'), amount);
  }
  /** Clicks Send and waits until exactly `count` requests have been made and the form is usable again. */
  async function sendAndSettle(user, f, count) {
    await user.click(send());
    await waitFor(() => expect(f.callsTo(URL)).toHaveLength(count));
    await waitFor(() => expect(send()).toBeEnabled());
  }

  it('reuses the key when a network failure is retried with the same payload', async () => {
    const { f, user } = await open(sequence(networkDown(), res(201, { id: 3, amount: '5' })));
    await fill(user);
    await user.click(send());
    expect(await screen.findByText('Cannot reach the server')).toBeInTheDocument();
    await user.click(send());
    expect(await screen.findByRole('status')).toHaveTextContent('transaction #3');
    const [first, second] = keysOf(f, URL);
    expect(f.callsTo(URL)).toHaveLength(2);
    expect(second).toBe(first);
  });

  it.each([502, 503, 504])('reuses the key after a %i', async (status) => {
    const { f, user } = await open(sequence(res(status), res(201, { id: 3, amount: '5' })));
    await fill(user);
    await sendAndSettle(user, f, 1);
    await sendAndSettle(user, f, 2);
    const [first, second] = keysOf(f, URL);
    expect(second).toBe(first);
  });

  it('reuses the key after a 500 with no JSON body, but not after a 500 with an error body', async () => {
    const { f, user } = await open(
      sequence(res(500), res(500, { error: 'Internal error' }), res(500, { error: 'Internal error' }))
    );
    await fill(user);
    await sendAndSettle(user, f, 1);
    await sendAndSettle(user, f, 2);
    await sendAndSettle(user, f, 3);
    const [k1, k2, k3] = keysOf(f, URL);
    expect(k2).toBe(k1); // bare 500: unknown outcome, same key
    expect(k3).not.toBe(k2); // 500 with a body is a definite answer: new key
  });

  it('uses a new key after a successful transfer', async () => {
    const { f, user } = await open(sequence(res(201, { id: 1, amount: '5' })));
    await fill(user);
    await sendAndSettle(user, f, 1);
    await fill(user); // the same values typed again are a new request
    await sendAndSettle(user, f, 2);
    const [first, second] = keysOf(f, URL);
    expect(second).not.toBe(first);
  });

  it.each([
    [400, { error: 'Currency mismatch' }],
    [409, { error: 'Idempotency key already used with a different request' }],
    [422, { error: 'Insufficient funds' }],
    [429, undefined],
  ])('uses a new key after a %i answer', async (status, body) => {
    const { f, user } = await open(sequence(res(status, body), res(201, { id: 1, amount: '5' })));
    await fill(user);
    await sendAndSettle(user, f, 1);
    await sendAndSettle(user, f, 2);
    const [first, second] = keysOf(f, URL);
    expect(second).not.toBe(first);
  });

  it('uses a new key when the amount, receiver or note is edited after a network failure', async () => {
    const { f, user } = await open(sequence(networkDown()));
    await fill(user, 'bob', '5');
    await sendAndSettle(user, f, 1);
    await fill(user, 'bob', '6'); // amount edited
    await sendAndSettle(user, f, 2);
    await fill(user, 'carol', '6'); // receiver edited
    await sendAndSettle(user, f, 3);
    await user.type(screen.getByLabelText(/^Note/), 'hi'); // note edited
    await sendAndSettle(user, f, 4);
    expect(new Set(keysOf(f, URL)).size).toBe(4);
  });
});

describe('AmountForm (withdraw / admin deposit): Idempotency-Key reuse', () => {
  const setup = (action) => {
    render(<AmountForm title="Withdraw" submitText="Go" action={action} />);
    return { user: userEvent.setup(), input: screen.getByLabelText('Amount') };
  };
  const go = () => screen.getByRole('button', { name: 'Go' });

  it('reuses the key for a retry after a network failure and replaces it after success', async () => {
    const action = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(0, 'Cannot reach the server'))
      .mockResolvedValue({ id: 4 });
    const { user, input } = setup(action);
    await user.type(input, '10');
    await user.click(go());
    await screen.findByText('Cannot reach the server');
    expect(input).toHaveValue(10); // kept for the retry
    await user.click(go());
    await screen.findByRole('status');
    expect(action.mock.calls[1][1]).toBe(action.mock.calls[0][1]);

    await user.type(input, '10'); // the same amount again after success is a NEW request
    await user.click(go());
    await waitFor(() => expect(action).toHaveBeenCalledTimes(3));
    expect(action.mock.calls[2][1]).not.toBe(action.mock.calls[1][1]);
  });

  it('uses a new key after a 4xx and after the amount is edited', async () => {
    const action = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(422, 'Insufficient funds'))
      .mockRejectedValueOnce(new ApiError(503, 'Request failed (503)'))
      .mockRejectedValueOnce(new ApiError(0, 'Cannot reach the server'))
      .mockResolvedValue({ id: 4 });
    const { user, input } = setup(action);
    await user.type(input, '10');
    await user.click(go());
    await screen.findByText('Insufficient funds');
    await user.click(go()); // retry of a definite failure: new key
    await screen.findByText('Request failed (503)');
    await user.clear(input);
    await user.type(input, '11'); // edited after an unknown outcome: new key
    await user.click(go());
    await screen.findByText('Cannot reach the server');
    expect(new Set(action.mock.calls.map((c) => c[1])).size).toBe(3);
  });

  it('treats the extra fields (admin wallet id) as part of the payload', async () => {
    const action = vi.fn().mockRejectedValue(new ApiError(0, 'Cannot reach the server'));
    const Wrapper = ({ walletId }) => (
      <AmountForm title="Deposit" submitText="Go" action={action} extraPayload={{ walletId }} />
    );
    const { rerender } = render(<Wrapper walletId="5" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Amount'), '10');
    await user.click(go());
    await screen.findByText('Cannot reach the server');
    await user.click(go()); // same wallet: same key
    await waitFor(() => expect(action).toHaveBeenCalledTimes(2));
    rerender(<Wrapper walletId="6" />); // other wallet: new key
    await user.click(go());
    await waitFor(() => expect(action).toHaveBeenCalledTimes(3));
    const [a, b, c] = action.mock.calls.map((x) => x[1]);
    expect(b).toBe(a);
    expect(c).not.toBe(b);
  });
});

describe('Deposit request page: Idempotency-Key reuse', () => {
  const URL = 'POST /api/deposit-requests';

  it('reuses the key after a network failure and uses a new one once the request was created', async () => {
    const created = (id) => res(201, { id, status: 'PENDING', amount: '25' });
    const f = mockFetch({
      ...authedHandlers(),
      'GET /api/deposit-requests': res(200, page()),
      [URL]: sequence(networkDown(), created(5), created(6)),
    });
    renderApp('/deposits', { token: 't' });
    const user = userEvent.setup();
    await screen.findByRole('heading', { name: 'Request a deposit' });
    const send = () => screen.getByRole('button', { name: 'Send request' });
    await user.type(screen.getByLabelText('Amount'), '25');
    await user.click(send());
    await screen.findByText('Cannot reach the server');
    await user.click(send());
    await screen.findByRole('status');
    await user.type(screen.getByLabelText('Amount'), '25');
    await user.click(send());
    await waitFor(() => expect(f.callsTo(URL)).toHaveLength(3));
    const [a, b, c] = keysOf(f, URL);
    expect(b).toBe(a);
    expect(c).not.toBe(b);
  });
});
