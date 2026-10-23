import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, getToken, newIdempotencyKey, setToken, setUnauthorizedHandler } from '../src/api/client.js';
import { mockFetch, res } from './helpers.jsx';

describe('api client', () => {
  let f;
  beforeEach(() => {
    f = mockFetch({});
  });

  it('sends no Authorization header without a token', async () => {
    f = mockFetch({ 'GET /api/wallets/me': res(200, { id: 1 }) });
    await api.myWallet();
    expect(f.calls[0].init.headers.Authorization).toBeUndefined();
  });

  it('sends the bearer token once set, and stops after clearing', async () => {
    f = mockFetch({ 'GET /api/wallets/me': res(200, { id: 1 }) });
    setToken('abc.def');
    await api.myWallet();
    expect(f.calls[0].init.headers.Authorization).toBe('Bearer abc.def');
    setToken(null);
    await api.myWallet();
    expect(f.calls[1].init.headers.Authorization).toBeUndefined();
  });

  it('persists the token in sessionStorage and removes it on clear', () => {
    setToken('t1');
    expect(sessionStorage.getItem('wallet.token')).toBe('t1');
    expect(getToken()).toBe('t1');
    setToken(null);
    expect(sessionStorage.getItem('wallet.token')).toBeNull();
    expect(getToken()).toBeNull();
  });

  it('restores the token from sessionStorage at module load', async () => {
    sessionStorage.setItem('wallet.token', 'persisted');
    vi.resetModules();
    const fresh = await import('../src/api/client.js');
    expect(fresh.getToken()).toBe('persisted');
  });

  it('sends JSON body with Content-Type for POST and none for GET', async () => {
    f = mockFetch({
      'POST /api/auth/login': res(200, { token: 'x' }),
      'GET /api/users/me': res(200, {}),
    });
    const out = await api.login('bob', 'pw');
    expect(out).toEqual({ token: 'x' });
    expect(f.calls[0].init.headers['Content-Type']).toBe('application/json');
    expect(f.calls[0].body).toEqual({ username: 'bob', password: 'pw' });
    await api.me();
    expect(f.calls[1].init.headers['Content-Type']).toBeUndefined();
    expect(f.calls[1].init.body).toBeUndefined();
  });

  it('returns null for 204 responses', async () => {
    f = mockFetch({ 'PATCH /api/users/me': res(204) });
    expect(await api.updateEmail('a@b.co')).toBeNull();
  });

  it('sends the Idempotency-Key header on withdraw, transfer and admin deposit', async () => {
    f = mockFetch({
      'POST /api/wallets/me/withdraw': res(200, {}),
      'POST /api/transactions/transfer': res(200, {}),
      'POST /api/admin/wallets/5/deposit': res(200, {}),
    });
    await api.withdraw('1', 'k1');
    await api.transfer({ amount: '1' }, 'k2');
    await api.adminDeposit(5, '1', 'k3');
    expect(f.calls.map((c) => c.init.headers['Idempotency-Key'])).toEqual(['k1', 'k2', 'k3']);
  });

  it('has no self-service deposit call any more', () => {
    expect(api.deposit).toBeUndefined();
  });

  it('omits the Idempotency-Key header when none is given', async () => {
    f = mockFetch({ 'POST /api/auth/login': res(200, {}) });
    await api.login('a', 'b');
    expect(f.calls[0].init.headers['Idempotency-Key']).toBeUndefined();
  });

  it('generates distinct idempotency keys', () => {
    const a = newIdempotencyKey();
    expect(a).toMatch(/^[0-9a-f-]{36}$/);
    expect(newIdempotencyKey()).not.toBe(a);
  });

  it('builds query strings and skips empty values', async () => {
    f = mockFetch({ 'GET /api/transactions': res(200, {}) });
    await api.transactions({ page: 0, size: 20, status: '', type: undefined, from: null, sort: 'amount,desc' });
    const url = new URL(f.calls[0].url, 'http://x');
    expect(url.searchParams.get('page')).toBe('0');
    expect(url.searchParams.get('size')).toBe('20');
    expect(url.searchParams.get('sort')).toBe('amount,desc');
    expect([...url.searchParams.keys()].sort()).toEqual(['page', 'size', 'sort']);
  });

  it('has no query string when params are empty', async () => {
    f = mockFetch({ 'GET /api/transactions': res(200, {}) });
    await api.transactions({});
    expect(f.calls[0].url).toBe('/api/transactions');
  });

  it('encodes path ids', async () => {
    f = mockFetch({ 'GET /api/wallets/a%2Fb': res(200, {}) });
    await api.wallet('a/b');
    expect(f.calls[0].url).toBe('/api/wallets/a%2Fb');
  });

  it.each([
    [400, 'Validation failed'],
    [403, 'Forbidden'],
    [404, 'Not found'],
    [409, 'Duplicate username'],
    [422, 'Insufficient funds'],
    [500, 'Internal error'],
  ])('maps %i to ApiError with the server error text', async (status, error) => {
    f = mockFetch({ 'POST /api/transactions/transfer': res(status, { status, error }) });
    const err = await api.transfer({}, 'k').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(status);
    expect(err.message).toBe(error);
  });

  it('falls back to a generic message for empty or non-JSON error bodies', async () => {
    f = mockFetch({ 'GET /api/wallets/me': res(403) });
    const err = await api.myWallet().catch((e) => e);
    expect(err.status).toBe(403);
    expect(err.message).toBe('Request failed (403)');
  });

  it('falls back to a generic message when the JSON body has no error field', async () => {
    f = mockFetch({ 'GET /api/wallets/me': res(400, { message: 'x' }) });
    const err = await api.myWallet().catch((e) => e);
    expect(err.message).toBe('Request failed (400)');
  });

  it('on 401 with a token: clears the token and calls the unauthorized handler', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    setToken('expired');
    f = mockFetch({ 'GET /api/wallets/me': res(401) });
    const err = await api.myWallet().catch((e) => e);
    expect(err.status).toBe(401);
    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem('wallet.token')).toBeNull();
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('on 401 without a token (bad login): does not call the handler and keeps the server message', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    f = mockFetch({ 'POST /api/auth/login': res(401, { status: 401, error: 'Bad credentials' }) });
    const err = await api.login('a', 'b').catch((e) => e);
    expect(err.message).toBe('Bad credentials');
    expect(handler).not.toHaveBeenCalled();
  });

  it('turns a network failure into ApiError status 0', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await api.myWallet().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(0);
    expect(err.message).toBe('Cannot reach the server');
  });

  it('does not log out on a network failure', async () => {
    setToken('keep');
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    await api.myWallet().catch(() => {});
    expect(getToken()).toBe('keep');
  });

  it('serialises endpoint bodies as documented', async () => {
    f = mockFetch({
      'POST /api/auth/register': res(200, {}),
      'POST /api/users/me/password': res(200, {}),
      'PATCH /api/admin/users/3/status': res(200, {}),
    });
    await api.register('u', 'e@x.co', 'pw');
    await api.changePassword('old', 'new');
    await api.setUserEnabled(3, false);
    expect(f.calls.map((c) => c.body)).toEqual([
      { username: 'u', email: 'e@x.co', password: 'pw' },
      { currentPassword: 'old', newPassword: 'new' },
      { enabled: false },
    ]);
  });
});

describe('api client: logout and revoked tokens', () => {
  it('logout posts to /api/auth/logout with the bearer token and no body, and returns null for 204', async () => {
    setToken('tok');
    const f = mockFetch({ 'POST /api/auth/logout': res(204) });
    expect(await api.logout()).toBeNull();
    expect(f.calls[0].method).toBe('POST');
    expect(f.calls[0].init.headers.Authorization).toBe('Bearer tok');
    expect(f.calls[0].init.body).toBeUndefined();
    expect(f.calls[0].init.headers['Content-Type']).toBeUndefined();
  });

  it('changePassword returns the fresh token from the 200 response', async () => {
    mockFetch({ 'POST /api/users/me/password': res(200, { token: 'fresh', tokenType: 'Bearer' }) });
    expect(await api.changePassword('old', 'new')).toEqual({ token: 'fresh', tokenType: 'Bearer' });
  });

  it('a 401 for a token that is no longer the current one does not clear the current session', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    setToken('old');
    let answer;
    globalThis.fetch = vi.fn(() => new Promise((r) => (answer = () => r(res(401)))));
    const pending = api.myWallet().catch((e) => e);
    setToken('new');
    answer();
    const err = await pending;
    expect(err.status).toBe(401);
    expect(getToken()).toBe('new');
    expect(handler).not.toHaveBeenCalled();
  });

  it('a 401 on the very same token still clears it and calls the handler once', async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    setToken('revoked');
    mockFetch({ 'GET /api/users/me': res(401) });
    await api.me().catch(() => {});
    expect(getToken()).toBeNull();
    expect(handler).toHaveBeenCalledTimes(1);
  });
});

describe('api client: rate limiting (HTTP 429)', () => {
  const limited = (retryAfter) => ({
    ...res(429, { status: 429, error: 'Too many requests, try again in 12 seconds' }),
    headers: { get: (name) => (name.toLowerCase() === 'retry-after' ? retryAfter : null) },
  });

  it('maps 429 with Retry-After to a clear message with the seconds', async () => {
    mockFetch({ 'POST /api/auth/login': limited('12') });
    const err = await api.login('alice', 'x').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(429);
    expect(err.retryAfter).toBe(12);
    expect(err.message).toBe('Too many attempts. Try again in 12 seconds.');
  });

  it('uses the singular for one second', async () => {
    mockFetch({ 'POST /api/auth/login': limited('1') });
    const err = await api.login('alice', 'x').catch((e) => e);
    expect(err.message).toBe('Too many attempts. Try again in 1 second.');
  });

  it('falls back to a generic message when Retry-After is missing or not a positive whole number', async () => {
    for (const value of [null, '', 'soon', '0', '-5', '1.5', 'Wed, 21 Oct 2026 07:28:00 GMT']) {
      mockFetch({ 'POST /api/auth/register': limited(value) });
      const err = await api.register('alice', 'a@b.co', 'secret123').catch((e) => e);
      expect(err.status).toBe(429);
      expect(err.retryAfter).toBeNull();
      expect(err.message).toBe('Too many attempts. Please wait a moment and try again.');
    }
  });

  it('works when the response has no headers object at all', async () => {
    mockFetch({ 'POST /api/auth/login': res(429, { error: 'x' }) });
    const err = await api.login('alice', 'x').catch((e) => e);
    expect(err.message).toBe('Too many attempts. Please wait a moment and try again.');
  });

  it('does not sign the user out and does not retry', async () => {
    setToken('tok');
    let cleared = 0;
    setUnauthorizedHandler(() => cleared++);
    const f = mockFetch({ 'POST /api/transactions/transfer': limited('30') });
    const err = await api.transfer({}, 'k').catch((e) => e);
    expect(err.status).toBe(429);
    expect(getToken()).toBe('tok');
    expect(cleared).toBe(0);
    expect(f.calls).toHaveLength(1);
    setToken(null);
    setUnauthorizedHandler(() => {});
  });
});
