import { useCallback, useEffect, useState } from 'react';

/**
 * Runs an async loader whenever `deps` change and tracks loading and error state.
 * Returns `{ data, error, loading, reload }`.
 */
export function useFetch(loader, deps) {
  const [state, setState] = useState({ data: null, error: '', loading: true });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState((s) => ({ ...s, loading: true, error: '' }));
    loader()
      .then((data) => !cancelled && setState({ data, error: '', loading: false }))
      .catch((e) => !cancelled && setState({ data: null, error: e.message, loading: false }));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ...state, reload };
}
