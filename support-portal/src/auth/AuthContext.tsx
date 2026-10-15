import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getToken, onSessionEnded, setToken } from '../api/client';
import { authApi } from '../api/endpoints';
import type { Permission, Staff } from '../api/types';
import { hasIdleExpired, readInactivityNotice, startActivityClock, useInactivityLogout, writeInactivityNotice } from './inactivity';

interface AuthState {
  staff: Staff | null;
  loading: boolean;
  /** Whether the signed-in member may do this (as reported by the backend). The backend enforces it regardless. */
  can: (permission: Permission) => boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** The member was last signed out for inactivity: the sign-in page says so, until they sign in again. */
  inactivityLogout: boolean;
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
  const [inactivityLogout, setInactivityLogout] = useState(readInactivityNotice);
  const queryClient = useQueryClient();

  // However the session ends (here, in another tab, or refused by the backend) nothing of it stays on screen or in the page's memory.
  useEffect(() => {
    onSessionEnded(() => {
      setStaff(null);
      setInactivityLogout(readInactivityNotice());
      queryClient.clear();
    });
  }, [queryClient]);

  // 15 minutes without interaction: the notice first, so that the other tabs (told by the token going away) can read it.
  const endForInactivity = useCallback(() => {
    writeInactivityNotice(true);
    setInactivityLogout(true);
    setToken(null);
    setStaff(null);
    queryClient.clear();
  }, [queryClient]);
  const reportActivity = useCallback(() => {
    // Only renews a session that is still there: an answer arriving after sign-out must not bring it back.
    authApi.activity().then((renewed) => { if (getToken()) setToken(renewed.token); }).catch(() => { /* a refusal already ended the session */ });
  }, []);
  useInactivityLogout(staff !== null, endForInactivity, reportActivity);

  useEffect(() => {
    let active = true;
    if (getToken() && hasIdleExpired()) {
      // The page was left (closed, asleep) for 15 minutes or more: no data is asked for, the session is over.
      endForInactivity();
      setLoading(false);
    } else if (getToken()) {
      authApi.me()
        .then((s) => { if (active) setStaff(s); })
        .catch(() => setToken(null))
        .finally(() => { if (active) setLoading(false); });
    } else {
      setLoading(false);
    }
    return () => { active = false; };
  }, [endForInactivity]);

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password });
    // A new session: its inactivity clock starts now and the "logged out for inactivity" message is no longer true.
    writeInactivityNotice(false);
    setInactivityLogout(false);
    startActivityClock();
    setToken(res.token);
    setStaff(res.staff);
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setStaff(null);
  }, []);

  const can = useCallback((permission: Permission) => staff?.permissions.includes(permission) ?? false, [staff]);

  const value = useMemo<AuthState>(() => ({ staff, loading, can, login, logout, inactivityLogout }), [staff, loading, can, login, logout, inactivityLogout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
