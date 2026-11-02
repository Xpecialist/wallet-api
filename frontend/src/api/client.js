// Single API client: base URL, auth header, JSON handling and error parsing.

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const STORAGE_KEY = 'wallet.token';

let token = sessionStorage.getItem(STORAGE_KEY);
let onUnauthorized = () => {};

/**
 * Error thrown for any non-2xx response; `message` is the server's error text when available.
 * `retryAfter` (seconds) is set on a 429 that carried a usable Retry-After header.
 */
export class ApiError extends Error {
  constructor(status, message, retryAfter = null) {
    super(message);
    this.status = status;
    this.retryAfter = retryAfter;
    this.hasBody = true; // false only for a response without a JSON error body (set by parseError)
  }
}

/**
 * True when the outcome of a request is unknown, so repeating it with the SAME Idempotency-Key is the safe retry:
 * no response at all (status 0), a gateway/availability status (502, 503, 504) or a 500 that carried no JSON error
 * body (not our server's answer). Any other answer, including every 4xx, is definite.
 */
export function isIndeterminate(e) {
  if (!(e instanceof ApiError)) return false;
  return e.status === 0 || e.status === 502 || e.status === 503 || e.status === 504 || (e.status === 500 && !e.hasBody);
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

/** Generates a fresh Idempotency-Key; see useIdempotentSubmit for when a form keeps one across retries. */
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

/** Seconds from a Retry-After header (the API sends whole seconds); null when absent or not a positive number. */
function retryAfterSeconds(res) {
  const raw = res.headers && typeof res.headers.get === 'function' ? res.headers.get('Retry-After') : null;
  const seconds = raw !== null && /^\d+$/.test(raw.trim()) ? Number(raw) : NaN;
  return seconds > 0 ? seconds : null;
}

async function parseError(res) {
  if (res.status === 429) {
    // rate limited: a fixed, friendly message for every form (no retry loop; the user decides when to try again)
    const wait = retryAfterSeconds(res);
    const message = wait
      ? `Too many attempts. Try again in ${wait} second${wait === 1 ? '' : 's'}.`
      : 'Too many attempts. Please wait a moment and try again.';
    return new ApiError(429, message, wait);
  }
  let message = `Request failed (${res.status})`;
  let hasBody = false;
  try {
    const body = await res.json();
    if (body && body.error) {
      message = body.error;
      hasBody = true;
    }
  } catch {
    // empty or non-JSON body: keep the generic message
  }
  const error = new ApiError(res.status, message);
  error.hasBody = hasBody;
  return error;
}

async function request(method, path, { body, params, idempotencyKey } = {}) {
  const headers = {};
  const sentToken = token; // a late 401 must not sign out a newer session (e.g. the logout call itself)
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

  if (res.status === 401 && sentToken && token === sentToken) {
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
  // revokes every token of the user (all devices); 204
  logout: () => request('POST', '/api/auth/logout'),

  // wallets
  myWallet: () => request('GET', '/api/wallets/me'),
  wallet: (id) => request('GET', `/api/wallets/${encodeURIComponent(id)}`),
  withdraw: (amount, key) => request('POST', '/api/wallets/me/withdraw', { body: { amount }, idempotencyKey: key }),

  // transactions
  transfer: (body, key) => request('POST', '/api/transactions/transfer', { body, idempotencyKey: key }),
  transactions: (params) => request('GET', '/api/transactions', { params }),
  transaction: (id) => request('GET', `/api/transactions/${id}`),

  // deposit requests (money is only added once an admin approves)
  depositRequests: (params) => request('GET', '/api/deposit-requests', { params }),
  createDepositRequest: (body, key) => request('POST', '/api/deposit-requests', { body, idempotencyKey: key }),

  // profile
  me: () => request('GET', '/api/users/me'),
  updateEmail: (email) => request('PATCH', '/api/users/me', { body: { email } }),
  // answers 200 with a NEW token (the old one is revoked): the caller must store it with setToken
  changePassword: (currentPassword, newPassword) =>
    request('POST', '/api/users/me/password', { body: { currentPassword, newPassword } }),

  // admin
  adminUsers: (params) => request('GET', '/api/admin/users', { params }),
  setUserEnabled: (id, enabled) => request('PATCH', `/api/admin/users/${id}/status`, { body: { enabled } }),
  adminTransactions: (params) => request('GET', '/api/admin/transactions', { params }),
  adminDepositRequests: (params) => request('GET', '/api/admin/deposit-requests', { params }),
  approveDepositRequest: (id, decisionNote) =>
    request('POST', `/api/admin/deposit-requests/${id}/approve`, { body: decisionNote ? { decisionNote } : {} }),
  rejectDepositRequest: (id, decisionNote) =>
    request('POST', `/api/admin/deposit-requests/${id}/reject`, { body: { decisionNote } }),
  adminDeposit: (walletId, amount, key) =>
    request('POST', `/api/admin/wallets/${encodeURIComponent(walletId)}/deposit`, {
      body: { amount },
      idempotencyKey: key,
    }),
};
