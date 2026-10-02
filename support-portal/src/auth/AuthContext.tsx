import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { getToken, onSessionEnded, setToken } from '../api/client';
import { authApi } from '../api/endpoints';
import type { Permission, Staff } from '../api/types';

interface AuthState {
  staff: Staff | null;
  loading: boolean;
  /** Whether the signed-in member may do this (as reported by the backend). The backend enforces it regardless. */
  can: (permission: Permission) => boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

/**
 * Support staff sign in with their own staff login (never a customer's). The backend decides who may use
 * the support API on every request; the portal just keeps the session and sends people back to the
 * sign-in page when the backend stops accepting it.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [staff, setStaff] = useState<Staff | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    onSessionEnded(() => setStaff(null));
  }, []);

  useEffect(() => {
    let active = true;
    if (getToken()) {
      authApi.me()
        .then((s) => { if (active) setStaff(s); })
        .catch(() => setToken(null))
        .finally(() => { if (active) setLoading(false); });
    } else {
      setLoading(false);
    }
    return () => { active = false; };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password });
    setToken(res.token);
    setStaff(res.staff);
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setStaff(null);
  }, []);

  const can = useCallback((permission: Permission) => staff?.permissions.includes(permission) ?? false, [staff]);

  const value = useMemo<AuthState>(() => ({ staff, loading, can, login, logout }), [staff, loading, can, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
