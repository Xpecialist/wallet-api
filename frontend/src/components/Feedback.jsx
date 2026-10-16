/** Error or success banner; renders nothing when there is no message. */
export function Alert({ type = 'error', children }) {
  if (!children) return null;
  return (
    <div className={`alert alert-${type}`} role={type === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

/** Simple loading indicator. */
export function Loading({ text = 'Loading...' }) {
  return <p className="muted">{text}</p>;
}
