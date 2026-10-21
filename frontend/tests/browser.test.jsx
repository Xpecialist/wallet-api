import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DataTable } from '../src/components/DataTable.jsx';
import { Pagination } from '../src/components/Pagination.jsx';
import { TransactionBrowser } from '../src/components/TransactionBrowser.jsx';
import { authedHandlers, mockFetch, page, renderApp, res } from './helpers.jsx';

const TX = [
  { id: 1, type: 'TRANSFER', senderWalletId: 7, receiverWalletId: 8, amount: '5', status: 'COMPLETED', note: 'lunch', createdAt: '2026-01-01T10:00:00' },
  { id: 2, type: 'TRANSFER', senderWalletId: 8, receiverWalletId: 7, amount: '3.5', status: 'COMPLETED', note: null, createdAt: '2026-01-02T10:00:00' },
  { id: 3, type: 'DEPOSIT', senderWalletId: null, receiverWalletId: 7, amount: '10', status: 'COMPLETED', createdAt: '2026-01-03T10:00:00' },
  { id: 4, type: 'WITHDRAWAL', senderWalletId: 7, receiverWalletId: null, amount: '1', status: 'COMPLETED', createdAt: '2026-01-04T10:00:00' },
];

const q = (call) => new URL(call.url, 'http://x').searchParams;

describe('Pagination', () => {
  it('renders nothing without data', () => {
    const { container } = render(<Pagination data={null} onPage={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('disables Previous on the first page and Next on the last', () => {
    const { rerender } = render(<Pagination data={{ page: 0, totalPages: 3, totalElements: 25 }} onPage={() => {}} />);
    expect(screen.getByText('Page 1 of 3 (25 total)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
    rerender(<Pagination data={{ page: 2, totalPages: 3, totalElements: 25 }} onPage={() => {}} />);
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('shows page 0 of 0 and disables both buttons when empty', () => {
    render(<Pagination data={{ page: 0, totalPages: 0, totalElements: 0 }} onPage={() => {}} />);
    expect(screen.getByText('Page 0 of 0 (0 total)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('calls onPage with the neighbouring page numbers', async () => {
    const onPage = vi.fn();
    render(<Pagination data={{ page: 1, totalPages: 3, totalElements: 25 }} onPage={onPage} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(onPage.mock.calls).toEqual([[2], [0]]);
  });

  it('single page disables both', () => {
    render(<Pagination data={{ page: 0, totalPages: 1, totalElements: 3 }} onPage={() => {}} />);
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });
});

describe('DataTable', () => {
  const columns = [
    { key: 'id', header: '#' },
    { key: 'name', header: 'Name', render: (r) => r.name.toUpperCase() },
  ];

  it.each([[[]], [null], [undefined]])('shows the empty text for %j rows', (rows) => {
    render(<DataTable columns={columns} rows={rows} emptyText="Nothing here" />);
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('uses the default empty text', () => {
    render(<DataTable columns={columns} rows={[]} />);
    expect(screen.getByText('Nothing to show.')).toBeInTheDocument();
  });

  it('renders headers, raw values and custom renders', () => {
    render(<DataTable columns={columns} rows={[{ id: 1, name: 'ann' }, { id: 2, name: 'bo' }]} />);
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['#', 'Name']);
    expect(screen.getByText('ANN')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('calls onRowClick with the row', async () => {
    const onRowClick = vi.fn();
    const rows = [{ id: 1, name: 'ann' }];
    render(<DataTable columns={columns} rows={rows} onRowClick={onRowClick} />);
    await userEvent.setup().click(screen.getByText('ANN'));
    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });
});

describe('TransactionBrowser', () => {
  const setup = (handlers = {}, props = {}) => {
    const fetcher = vi.fn(async () => page(TX, { totalElements: 4 }));
    const utils = render(<TransactionBrowser fetcher={fetcher} walletId={7} currency="USD" {...props} />);
    return { fetcher, user: userEvent.setup(), ...utils, handlers };
  };
  const lastParams = (fetcher) => fetcher.mock.calls.at(-1)[0];

  it('loads with default query params: page 0, size 10, sort createdAt,desc and no filters', async () => {
    const { fetcher } = setup();
    await screen.findByText('lunch');
    expect(lastParams(fetcher)).toEqual({
      from: '', to: '', minAmount: '', maxAmount: '', status: '', type: '',
      page: 0, size: '10', sort: 'createdAt,desc',
    });
  });

  it('shows direction tags and signed amounts relative to the wallet', async () => {
    setup();
    await screen.findByText('lunch');
    const rows = screen.getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('sent')).toBeInTheDocument();
    expect(within(rows[0]).getByText(/^-5[.,]00 USD$/)).toBeInTheDocument();
    expect(within(rows[1]).getByText('received')).toBeInTheDocument();
    expect(within(rows[1]).getByText(/^\+3[.,]50 USD$/)).toBeInTheDocument();
    expect(within(rows[2]).getByText('deposit')).toBeInTheDocument();
    expect(within(rows[3]).getByText('withdrawal')).toBeInTheDocument();
    expect(within(rows[3]).getByText(/^-1[.,]00 USD$/)).toBeInTheDocument();
  });

  it('shows type and unsigned amount when no walletId is given (admin view)', async () => {
    setup({}, { walletId: undefined });
    await screen.findByText('lunch');
    expect(screen.getByRole('columnheader', { name: 'Type' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Direction' })).not.toBeInTheDocument();
    expect(screen.queryByText(/^[+-]\d/)).not.toBeInTheDocument();
  });

  it('does not query until Apply is pressed, then sends all filters and resets to page 0', async () => {
    const { fetcher, user } = setup();
    await screen.findByText('lunch');
    const before = fetcher.mock.calls.length;
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-01' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-01-31' } });
    await user.type(screen.getByLabelText('Min amount'), '1.5');
    await user.type(screen.getByLabelText('Max amount'), '99');
    await user.selectOptions(screen.getByLabelText('Status'), 'COMPLETED');
    await user.selectOptions(screen.getByLabelText('Type'), 'DEPOSIT');
    expect(fetcher.mock.calls.length).toBe(before);
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(fetcher.mock.calls.length).toBe(before + 1));
    expect(lastParams(fetcher)).toMatchObject({
      from: '2026-01-01', to: '2026-01-31', minAmount: '1.5', maxAmount: '99',
      status: 'COMPLETED', type: 'DEPOSIT', page: 0,
    });
  });

  it('Reset clears the filters and queries again', async () => {
    const { fetcher, user } = setup();
    await screen.findByText('lunch');
    await user.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(lastParams(fetcher).status).toBe('FAILED'));
    await user.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(lastParams(fetcher).status).toBe(''));
    expect(screen.getByLabelText('Status')).toHaveValue('');
  });

  it('moves between pages with Next / Previous', async () => {
    const fetcher = vi.fn(async (p) => page(TX, { page: p.page, totalPages: 3, totalElements: 30 }));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} currency="USD" />);
    const user = userEvent.setup();
    await screen.findByText('Page 1 of 3 (30 total)');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 3 (30 total)');
    expect(lastParams(fetcher).page).toBe(1);
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    await screen.findByText('Page 1 of 3 (30 total)');
    expect(lastParams(fetcher).page).toBe(0);
  });

  it('changing page size, sort or filters goes back to page 0', async () => {
    const fetcher = vi.fn(async (p) => page(TX, { page: p.page, totalPages: 5, totalElements: 50 }));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} currency="USD" />);
    const user = userEvent.setup();
    await screen.findByText('Page 1 of 5 (50 total)');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 3 of 5 (50 total)');
    await user.selectOptions(screen.getByLabelText('Page size'), '50');
    await screen.findByText('Page 1 of 5 (50 total)');
    expect(lastParams(fetcher)).toMatchObject({ page: 0, size: '50' });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 5 (50 total)');
    await user.selectOptions(screen.getByLabelText('Sort by'), 'amount');
    await user.selectOptions(screen.getByLabelText('Order'), 'asc');
    await waitFor(() => expect(lastParams(fetcher)).toMatchObject({ page: 0, sort: 'amount,asc' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Page 2 of 5 (50 total)');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(lastParams(fetcher).page).toBe(0));
  });

  it('page size options are 5-100 and never exceed the API cap of 100', () => {
    setup();
    const opts = within(screen.getByLabelText('Page size')).getAllByRole('option').map((o) => Number(o.value));
    expect(opts).toEqual([5, 10, 20, 50, 100]);
    expect(Math.max(...opts)).toBeLessThanOrEqual(100);
  });

  it('shows the empty state when there are no transactions', async () => {
    const fetcher = vi.fn(async () => page([]));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} />);
    expect(await screen.findByText('No transactions.')).toBeInTheDocument();
    expect(screen.getByText('Page 0 of 0 (0 total)')).toBeInTheDocument();
  });

  it('shows a loading indicator, then the data', async () => {
    let done;
    const fetcher = vi.fn(() => new Promise((r) => (done = () => r(page(TX)))));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
    done();
    expect(await screen.findByText('lunch')).toBeInTheDocument();
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
  });

  it('shows the error state with the server message and no table', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('Invalid sort parameter'));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid sort parameter');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('recovers from an error when the query changes', async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValue(page(TX));
    render(<TransactionBrowser fetcher={fetcher} walletId={7} />);
    await screen.findByRole('alert');
    await userEvent.setup().selectOptions(screen.getByLabelText('Order'), 'asc');
    expect(await screen.findByText('lunch')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('History page and transaction detail', () => {
  it('queries /api/transactions with the default params and opens a transaction detail on row click', async () => {
    const f = mockFetch({
      ...authedHandlers(),
      'GET /api/transactions': res(200, page(TX)),
      'GET /api/transactions/1': res(200, { ...TX[0] }),
    });
    renderApp('/history', { token: 't' });
    await screen.findByText('lunch', { selector: 'td' });
    const call = f.callsTo('GET /api/transactions').at(-1);
    expect(q(call).get('page')).toBe('0');
    expect(q(call).get('size')).toBe('10');
    expect(q(call).get('sort')).toBe('createdAt,desc');
    expect([...q(call).keys()].sort()).toEqual(['page', 'size', 'sort']);

    const user = userEvent.setup();
    await user.click(screen.getByText('lunch', { selector: 'td' }));
    expect(await screen.findByRole('heading', { name: 'Transaction #1' })).toBeInTheDocument();
    expect(await screen.findByText('TRANSFER (sent)')).toBeInTheDocument();
    expect(screen.queryByText('Idempotency key')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('heading', { name: 'Transaction #1' })).not.toBeInTheDocument();
  });

  it('shows a 404 from the detail call inside the panel', async () => {
    mockFetch({
      ...authedHandlers(),
      'GET /api/transactions': res(200, page(TX)),
      'GET /api/transactions/1': res(404, { error: 'Transaction not found' }),
    });
    renderApp('/history', { token: 't' });
    await userEvent.setup().click(await screen.findByText('lunch', { selector: 'td' }));
    expect(await screen.findByText('Transaction not found')).toBeInTheDocument();
  });

  it('uses the wallet currency in History rows', async () => {
    mockFetch({ ...authedHandlers(), 'GET /api/transactions': res(200, page(TX)) });
    renderApp('/history', { token: 't' });
    expect(await screen.findByText(/^-5[.,]00 USD$/)).toBeInTheDocument();
  });
});
