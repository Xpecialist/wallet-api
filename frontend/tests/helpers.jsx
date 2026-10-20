import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import App from '../src/App.jsx';
import { setToken } from '../src/api/client.js';
import { AuthProvider } from '../src/auth/AuthContext.jsx';

/** Builds a fetch Response-like object. */
export function res(status, body) {
  const hasBody = body !== undefined;
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => {
      if (!hasBody) throw new SyntaxError('Unexpected end of JSON input');
      return body;
    },
  };
}

export const page = (content = [], extra = {}) => ({
  content,
  page: 0,
  size: 10,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  ...extra,
});

export const USER = { id: 2, username: 'alice', email: 'alice@example.com', role: 'USER', enabled: true, createdAt: '2026-01-02T03:04:05' };
export const ADMIN = { ...USER, id: 1, username: 'admin', email: 'admin@example.com', role: 'ADMIN' };
export const WALLET = { id: 7, userId: 2, username: 'alice', balance: '100.5000', currency: 'USD' };

/**
 * Routes mocked fetch calls: handlers is a map of "METHOD /path" -> response | function(url, init) -> response.
 * Unmatched calls fail the test loudly with a 500.
 */
export function mockFetch(handlers = {}) {
  const calls = [];
  const fn = vi.fn(async (url, init = {}) => {
    const method = init.method || 'GET';
    const path = String(url).split('?')[0];
    calls.push({ method, url: String(url), path, init, body: init.body ? JSON.parse(init.body) : undefined });
    const h = handlers[`${method} ${path}`];
    if (h === undefined) return res(500, { error: `unmocked ${method} ${path}` });
    return typeof h === 'function' ? h(String(url), init) : h;
  });
  globalThis.fetch = fn;
  fn.calls = calls;
  fn.callsTo = (key) => calls.filter((c) => `${c.method} ${c.path}` === key);
  return fn;
}

/** Renders the full App at a route, optionally with a token already stored (so the session is restored). */
export function renderApp(route = '/', { token } = {}) {
  setToken(token || null);
  return render(
    <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>
  );
}

export const authedHandlers = (user = USER, wallet = WALLET) => ({
  'GET /api/users/me': res(200, user),
  'GET /api/wallets/me': res(200, wallet),
  'GET /api/transactions': res(200, page()),
});
