import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { ApiError, api, getToken, setToken, setUnauthorizedHandler } from '../api/client.js';

const AuthContext = createContext(null);

/**
 * Holds the signed-in user's profile (including role) and wallet.
 * `ready` becomes true once the initial profile load has finished.
 */
export function AuthProvider({ children }) {
  const [authenticated, setAuthenticated] = useState(Boolean(getToken()));
  const [user, setUser] = useState(null);
  const [wallet, setWallet] = useState(null);
  const [ready, setReady] = useState(!getToken());
  // true after a sign-out whose server call failed (not a 401): the login page then warns that other devices stay valid
  const [logoutNotice, setLogoutNotice] = useState(false);

  const clear = useCallback(() => {
    setToken(null);
    setUser(null);
    setWallet(null);
    setAuthenticated(false);
  }, []);

  // A 401 on any authenticated call signs the user out; route guards then redirect to login.
  useEffect(() => {
    setUnauthorizedHandler(clear);
  }, [clear]);

  const refreshWallet = useCallback(async () => {
    const w = await api.myWallet();
    setWallet(w);
    return w;
  }, []);

  const refreshUser = useCallback(async () => {
    const u = await api.me();
    setUser(u);
    return u;
  }, []);

  // Load profile and wallet whenever a token is present.
  useEffect(() => {
    if (!authenticated) return undefined;
    let cancelled = false;
    // Independent loads: a failing wallet call must not discard the profile (a 401 still signs out via the handler).
    Promise.allSettled([api.me(), api.myWallet()])
      .then(([u, w]) => {
        if (cancelled) return;
        if (u.status === 'fulfilled') setUser(u.value);
        if (w.status === 'fulfilled') setWallet(w.value);
      })
      .finally(() => {
        if (!cancelled) setReady(true);
      });
    return () => {
      cancelled = true;
    };
  }, [authenticated]);

  const signIn = useCallback((newToken) => {
    setToken(newToken);
    setReady(false);
    setLogoutNotice(false);
    setAuthenticated(true);
  }, []);

  // Log out of all devices. The local session is always cleared at once. A 401 from the call means the token was already
  // revoked or expired: nothing is left to end, so that is not an error (and does not loop: see the client). Any other
  // failure (network error / status 0, 5xx, 429) means the token may still be valid on the server: remember that so the
  // login page can tell the user. Only a flag is kept, never the token.
  const signOut = useCallback(() => {
    api.logout().catch((e) => {
      const alreadyRevoked = e instanceof ApiError && e.status === 401;
      // a failure that arrives after the user signed in again must not warn about the old session
      if (!alreadyRevoked && !getToken()) setLogoutNotice(true);
    });
    clear();
  }, [clear]);

  const dismissLogoutNotice = useCallback(() => setLogoutNotice(false), []);

  const value = useMemo(
    () => ({
      authenticated,
      ready,
      user,
      wallet,
      isAdmin: user?.role === 'ADMIN',
      signIn,
      signOut,
      logoutNotice,
      dismissLogoutNotice,
      refreshWallet,
      refreshUser,
    }),
    [authenticated, ready, user, wallet, signIn, signOut, logoutNotice, dismissLogoutNotice, refreshWallet, refreshUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
