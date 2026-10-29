import { useCallback, useRef } from 'react';
import { isIndeterminate, newIdempotencyKey } from '../api/client.js';

/**
 * Idempotency-Key handling for one money form. Returns `run(payload, call)` where `call(key)` performs the request.
 *
 * The key is kept across attempts only while the outcome of the previous attempt is unknown (network error, status 0,
 * 502/503/504, or a 500 without a JSON error body) AND the payload is unchanged, so a retry cannot move money twice.
 * A new key is generated after a definite answer (2xx, any 4xx, a 500 with a body) and whenever the payload differs.
 */
export function useIdempotentSubmit() {
  const pending = useRef(null); // { fingerprint, key } of an attempt whose outcome is unknown

  return useCallback(async (payload, call) => {
    const fingerprint = JSON.stringify(payload);
    const key = pending.current && pending.current.fingerprint === fingerprint ? pending.current.key : newIdempotencyKey();
    pending.current = { fingerprint, key };
    try {
      const result = await call(key);
      pending.current = null;
      return result;
    } catch (e) {
      if (!isIndeterminate(e)) pending.current = null;
      throw e;
    }
  }, []);
}
