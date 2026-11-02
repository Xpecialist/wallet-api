import { useId, useState } from 'react';
import { useFetch } from '../utils/useFetch.js';
import { Alert, Loading } from './Feedback.jsx';
import { SelectField, TextField } from './Fields.jsx';
import { Pagination } from './Pagination.jsx';
import { TransactionDetail } from './TransactionDetail.jsx';
import { TransactionTable } from './TransactionTable.jsx';

const EMPTY_FILTERS = { from: '', to: '', minAmount: '', maxAmount: '', status: '', type: '' };

const STATUS_OPTIONS = [['', 'Any'], ['PENDING', 'Pending'], ['COMPLETED', 'Completed'], ['FAILED', 'Failed']];
const TYPE_OPTIONS = [['', 'Any'], ['TRANSFER', 'Transfer'], ['DEPOSIT', 'Deposit'], ['WITHDRAWAL', 'Withdrawal']];
const SORT_OPTIONS = [['createdAt', 'Date'], ['amount', 'Amount'], ['id', 'Id'], ['status', 'Status'], ['type', 'Type']];
const DIRECTION_OPTIONS = [['desc', 'Descending'], ['asc', 'Ascending']];
const SIZE_OPTIONS = [['5', '5'], ['10', '10'], ['20', '20'], ['50', '50'], ['100', '100']];

/**
 * Filterable, sortable, paged transaction list with a detail panel.
 * `fetcher(params)` loads a page (own history or the admin list). Pass `walletId` to show directions.
 */
export function TransactionBrowser({ fetcher, walletId, currency }) {
  const [draft, setDraft] = useState(EMPTY_FILTERS); // values being edited in the form
  const [filters, setFilters] = useState(EMPTY_FILTERS); // values applied to the query
  const [sortBy, setSortBy] = useState('createdAt');
  const [sortDir, setSortDir] = useState('desc');
  const [size, setSize] = useState('10');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [filtersOpen, setFiltersOpen] = useState(false); // only matters on phones
  const panelId = useId();

  const params = { ...filters, page, size, sort: `${sortBy},${sortDir}` };
  const { data, error, loading } = useFetch(() => fetcher(params), [JSON.stringify(params)]);

  const set = (name) => (e) => setDraft({ ...draft, [name]: e.target.value });

  function apply(e) {
    e.preventDefault();
    setFilters(draft);
    setPage(0);
  }

  function reset() {
    setDraft(EMPTY_FILTERS);
    setFilters(EMPTY_FILTERS);
    setPage(0);
  }

  return (
    <>
      <section className="card">
        {/* On phones the panel is collapsed behind this button (CSS only hides it; the fields stay in the DOM). */}
        <button
          type="button"
          className="secondary filters-toggle"
          aria-expanded={filtersOpen}
          aria-controls={panelId}
          onClick={() => setFiltersOpen(!filtersOpen)}
        >
          Filters
        </button>
        <div id={panelId} className={filtersOpen ? 'filter-panel is-open' : 'filter-panel'}>
        <form className="filters" onSubmit={apply}>
          <TextField label="From" type="date" value={draft.from} onChange={set('from')} />
          <TextField label="To" type="date" value={draft.to} onChange={set('to')} />
          <TextField label="Min amount" type="number" step="0.0001" min="0" value={draft.minAmount} onChange={set('minAmount')} />
          <TextField label="Max amount" type="number" step="0.0001" min="0" value={draft.maxAmount} onChange={set('maxAmount')} />
          <SelectField label="Status" options={STATUS_OPTIONS} value={draft.status} onChange={set('status')} />
          <SelectField label="Type" options={TYPE_OPTIONS} value={draft.type} onChange={set('type')} />
          <div className="filter-actions">
            <button type="submit">Apply</button>
            <button type="button" className="secondary" onClick={reset}>
              Reset
            </button>
          </div>
        </form>
        <div className="filters">
          <SelectField label="Sort by" options={SORT_OPTIONS} value={sortBy} onChange={(e) => { setSortBy(e.target.value); setPage(0); }} />
          <SelectField label="Order" options={DIRECTION_OPTIONS} value={sortDir} onChange={(e) => { setSortDir(e.target.value); setPage(0); }} />
          <SelectField label="Page size" options={SIZE_OPTIONS} value={size} onChange={(e) => { setSize(e.target.value); setPage(0); }} />
        </div>
        </div>
      </section>

      <section className="card">
        <Alert>{error}</Alert>
        {loading && !data && <Loading />}
        {data && (
          <>
            <TransactionTable
              rows={data.content}
              walletId={walletId}
              currency={currency}
              onRowClick={(t) => setSelectedId(t.id)}
            />
            <Pagination data={data} onPage={setPage} />
          </>
        )}
      </section>

      {selectedId != null && (
        <TransactionDetail id={selectedId} walletId={walletId} currency={currency} onClose={() => setSelectedId(null)} />
      )}
    </>
  );
}
