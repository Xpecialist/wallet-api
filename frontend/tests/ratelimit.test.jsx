import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { getToken } from '../src/api/client.js';
import { authedHandlers, mockFetch, renderApp, res } from './helpers.jsx';

/** A 429 as the API sends it: JSON error body plus a Retry-After header (whole seconds). */
const limited = (retryAfter) => ({
  ...res(429, { status: 429, error: 'Too many requests, try again in 12 seconds' }),
  headers: { get: (name) => (name.toLowerCase() === 'retry-after' ? retryAfter : null) },
});

describe('rate limiting (429) in the forms', () => {
  it('login shows the wait time, stays on the page and can be submitted again', async () => {
    const f = mockFetch({ 'POST /api/auth/login': limited('12') });
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 12 seconds.');
    expect(getToken()).toBeNull();
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled();
    expect(f.callsTo('POST /api/auth/login')).toHaveLength(1); // no automatic retry
  });

  it('login without Retry-After shows the generic wait message', async () => {
    mockFetch({ 'POST /api/auth/login': res(429, { status: 429, error: 'Too many requests, try again in 3 seconds' }) });
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'alice');
    await user.type(screen.getByLabelText('Password'), 'secret123');
    await user.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please wait a moment and try again.');
  });

  it('register shows the wait time', async () => {
    mockFetch({ 'POST /api/auth/register': limited('3600') });
    renderApp('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Username/), 'newuser');
    await user.type(screen.getByLabelText('Email'), 'new@example.com');
    await user.type(screen.getByLabelText(/^Password/), 'longenough1');
    await user.click(screen.getByRole('button', { name: 'Register' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 3600 seconds.');
    expect(getToken()).toBeNull();
  });

  it('password change shows the wait time and keeps the session', async () => {
    mockFetch({ ...authedHandlers(), 'POST /api/users/me/password': limited('600') });
    renderApp('/profile', { token: 't' });
    await screen.findByRole('heading', { name: 'Profile' });
    const s = screen.getByRole('heading', { name: 'Change password' }).closest('section');
    const user = userEvent.setup();
    await user.type(within(s).getByLabelText('Current password'), 'oldpassword');
    await user.type(within(s).getByLabelText(/^New password/), 'newpassword1');
    await user.click(within(s).getByRole('button', { name: 'Change password' }));
    expect(await within(s).findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 600 seconds.');
    expect(getToken()).toBe('t');
    expect(screen.getByRole('heading', { name: 'Profile' })).toBeInTheDocument();
  });

  it('transfer shows the wait time, keeps the typed values and the session', async () => {
    mockFetch({ ...authedHandlers(), 'POST /api/transactions/transfer': limited('30') });
    renderApp('/transfer', { token: 't' });
    await screen.findByRole('heading', { name: 'Transfer' });
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Receiver/), 'bob');
    await user.type(screen.getByLabelText('Amount'), '5');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 30 seconds.');
    expect(screen.getByLabelText(/^Receiver/)).toHaveValue('bob');
    expect(getToken()).toBe('t');
  });
});
