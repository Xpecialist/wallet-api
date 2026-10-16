import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken, setUnauthorizedHandler } from '../api/client.js';

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
    Promise.all([api.me(), api.myWallet()])
      .then(([u, w]) => {
        if (cancelled) return;
        setUser(u);
        setWallet(w);
      })
      .catch(() => {})
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
    setAuthenticated(true);
  }, []);

  const value = useMemo(
    () => ({
      authenticated,
      ready,
      user,
      wallet,
      isAdmin: user?.role === 'ADMIN',
      signIn,
      signOut: clear,
      refreshWallet,
      refreshUser,
    }),
    [authenticated, ready, user, wallet, signIn, clear, refreshWallet, refreshUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
