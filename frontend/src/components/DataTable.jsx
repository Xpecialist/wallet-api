/**
 * Generic table. `columns` is a list of `{ key, header, render?(row) }`.
 * When `onRowClick` is given, rows are clickable.
 */
export function DataTable({ columns, rows, onRowClick, emptyText = 'Nothing to show.' }) {
  if (!rows || rows.length === 0) return <p className="muted">{emptyText}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key}>{c.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              className={onRowClick ? 'clickable' : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((c) => (
                <td key={c.key}>{c.render ? c.render(row) : row[c.key]}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
