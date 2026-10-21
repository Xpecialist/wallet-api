// Single API client: base URL, auth header, JSON handling and error parsing.

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const STORAGE_KEY = 'wallet.token';

let token = sessionStorage.getItem(STORAGE_KEY);
let onUnauthorized = () => {};

/** Error thrown for any non-2xx response; `message` is the server's error text when available. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Stores the JWT in memory and sessionStorage (pass null to clear it). */
export function setToken(value) {
  token = value;
  if (value) sessionStorage.setItem(STORAGE_KEY, value);
  else sessionStorage.removeItem(STORAGE_KEY);
}

export const getToken = () => token;

/** Registers the callback invoked when an authenticated request gets a 401. */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

/** Generates a fresh Idempotency-Key; call once per form submit. */
export function newIdempotencyKey() {
  return crypto.randomUUID();
}

/** Builds a query string, skipping empty values. */
function toQuery(params) {
  const q = new URLSearchParams();
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, v);
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}

async function parseError(res) {
  let message = `Request failed (${res.status})`;
  try {
    const body = await res.json();
    if (body && body.error) message = body.error;
  } catch {
    // empty or non-JSON body: keep the generic message
  }
  return new ApiError(res.status, message);
}

async function request(method, path, { body, params, idempotencyKey } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let res;
  try {
    res = await fetch(`${BASE_URL}${path}${toQuery(params)}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server');
  }

  if (res.status === 401 && token) {
    setToken(null);
    onUnauthorized();
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return null;
  return res.json();
}

export const api = {
  // auth
  login: (username, password) => request('POST', '/api/auth/login', { body: { username, password } }),
  register: (username, email, password) =>
    request('POST', '/api/auth/register', { body: { username, email, password } }),

  // wallets
  myWallet: () => request('GET', '/api/wallets/me'),
  wallet: (id) => request('GET', `/api/wallets/${encodeURIComponent(id)}`),
  withdraw: (amount, key) => request('POST', '/api/wallets/me/withdraw', { body: { amount }, idempotencyKey: key }),

  // transactions
  transfer: (body, key) => request('POST', '/api/transactions/transfer', { body, idempotencyKey: key }),
  transactions: (params) => request('GET', '/api/transactions', { params }),
  transaction: (id) => request('GET', `/api/transactions/${id}`),

  // profile
  me: () => request('GET', '/api/users/me'),
  updateEmail: (email) => request('PATCH', '/api/users/me', { body: { email } }),
  changePassword: (currentPassword, newPassword) =>
    request('POST', '/api/users/me/password', { body: { currentPassword, newPassword } }),

  // admin
  adminUsers: (params) => request('GET', '/api/admin/users', { params }),
  setUserEnabled: (id, enabled) => request('PATCH', `/api/admin/users/${id}/status`, { body: { enabled } }),
  adminTransactions: (params) => request('GET', '/api/admin/transactions', { params }),
  adminDeposit: (walletId, amount, key) =>
    request('POST', `/api/admin/wallets/${encodeURIComponent(walletId)}/deposit`, {
      body: { amount },
      idempotencyKey: key,
    }),
};
