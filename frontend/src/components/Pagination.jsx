/** Previous/next controls for a Spring page object (`page`, `totalPages`, `totalElements`). */
export function Pagination({ data, onPage }) {
  if (!data) return null;
  const { page, totalPages, totalElements } = data;
  return (
    <div className="pagination">
      <button type="button" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <span>
        Page {totalPages === 0 ? 0 : page + 1} of {totalPages} ({totalElements} total)
      </span>
      <button type="button" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}
