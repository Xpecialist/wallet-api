/**
 * Generic table. `columns` is a list of
 * `{ key, header, render?(row), numeric?, cellClass?, actions?, title?(row) }`:
 * `numeric` right-aligns the column, `cellClass` adds a class to its cells, `actions` marks the
 * (button) column that stays pinned to the right edge, `title(row)` sets a tooltip on the cell
 * (use it for truncated text).
 * When `onRowClick` is given, rows are clickable. `rowClass(row)` adds a class to a row.
 * `cards` turns the table into stacked label/value cards on phones (see styles.css);
 * every cell carries `data-label` with its column header for that.
 */
export function DataTable({ columns, rows, onRowClick, rowClass, cards = false, emptyText = 'Nothing to show.' }) {
  if (!rows || rows.length === 0) return <p className="empty">{emptyText}</p>;
  const cellClasses = (c) => [c.numeric && 'num', c.actions && 'col-actions', c.cellClass].filter(Boolean).join(' ') || undefined;
  return (
    <div className={cards ? 'table-wrap table-cards' : 'table-wrap'}>
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={[c.numeric && 'num', c.actions && 'col-actions'].filter(Boolean).join(' ') || undefined}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              className={[onRowClick && 'clickable', rowClass && rowClass(row)].filter(Boolean).join(' ') || undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              {columns.map((c) => (
                <td key={c.key} className={cellClasses(c)} data-label={c.header} title={c.title ? c.title(row) : undefined}>
                  {c.render ? c.render(row) : row[c.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
