/** Coloured badge for a status (PENDING, APPROVED/COMPLETED or REJECTED/FAILED). */
export function StatusBadge({ status }) {
  return <span className={`tag tag-${String(status).toLowerCase()}`}>{status}</span>;
}
