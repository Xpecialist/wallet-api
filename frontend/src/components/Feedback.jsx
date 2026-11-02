/** Rate-limit (HTTP 429) messages built by api/client.js start with this text. */
const RATE_LIMIT_PREFIX = 'Too many attempts.';

/**
 * Error or success banner; renders nothing when there is no message.
 * Pages only keep `error.message`, so a rate-limit (429) message is recognised by its fixed text and
 * shown with the amber warning style instead of the error red (still role="alert").
 */
export function Alert({ type = 'error', children }) {
  if (!children) return null;
  const kind = type === 'error' && typeof children === 'string' && children.startsWith(RATE_LIMIT_PREFIX) ? 'warning' : type;
  return (
    <div className={`alert alert-${kind}`} role={type === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

/** Simple loading indicator. `page` centres it on the whole screen (session loading). */
export function Loading({ text = 'Loading...', page = false }) {
  return <p className={page ? 'loading loading-page' : 'loading'}>{text}</p>;
}
