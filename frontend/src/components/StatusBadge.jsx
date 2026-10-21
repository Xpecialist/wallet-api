/** Coloured badge for a deposit request status (PENDING, APPROVED or REJECTED). */
export function StatusBadge({ status }) {
  return <span className={`tag tag-${String(status).toLowerCase()}`}>{status}</span>;
}
