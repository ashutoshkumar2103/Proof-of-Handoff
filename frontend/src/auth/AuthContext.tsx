import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getToken, HttpError, onSessionEnded, setToken } from '../api/client';
import { authApi, paymentApi } from '../api/endpoints';
import type { AuthResponse, RegisterInput, User } from '../api/types';
import { clearPendingPayment, getPendingPayment } from '../lib/checkout';
import { PLAN_LABELS } from '../lib/format';
import { hasIdleExpired, readInactivityNotice, startActivityClock, useInactivityLogout, writeInactivityNotice } from './inactivity';

interface AuthState {
  user: User | null;
  loading: boolean;
  /** Both resolve to a message about a plan that was paid for before signing in (undefined when there was none). */
  login: (email: string, password: string) => Promise<string | undefined>;
  register: (input: RegisterInput) => Promise<string | undefined>;
  /** Shows an edited account straight away (the server's answer after a profile change). */
  updateUser: (user: User) => void;
  /** Takes over a fresh session (e.g. after a password change, which ends every other session). */
  startSession: (session: AuthResponse) => void;
  /** Applies a payment made before signing in, to the signed-in account. Resolves to a message for the customer. */
  applyPendingPayment: () => Promise<string | undefined>;
  logout: () => void;
  /** The person was last signed out for inactivity: the sign-in page says so, until they sign in again. */
  inactivityLogout: boolean;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [inactivityLogout, setInactivityLogout] = useState(readInactivityNotice);
  const queryClient = useQueryClient();

  // However the session ends (here, in another tab, or refused by the backend) nothing of it stays on screen or in the page's memory.
  useEffect(() => {
    onSessionEnded(() => {
      setUser(null);
      setInactivityLogout(readInactivityNotice());
      queryClient.clear();
    });
  }, [queryClient]);

  // 15 minutes without interaction: the notice first, so that the other tabs (told by the token going away) can read it.
  const endForInactivity = useCallback(() => {
    writeInactivityNotice(true);
    setInactivityLogout(true);
    setToken(null);
    setUser(null);
    queryClient.clear();
  }, [queryClient]);
  const reportActivity = useCallback(() => {
    // Only renews a session that is still there: an answer arriving after sign-out must not bring it back.
    authApi.activity().then((renewed) => { if (getToken()) setToken(renewed.token); }).catch(() => { /* a refusal already ended the session */ });
  }, []);
  useInactivityLogout(user !== null, endForInactivity, reportActivity);

  useEffect(() => {
    let active = true;
    if (getToken() && hasIdleExpired()) {
      // The page was left (closed, asleep) for 15 minutes or more: no data is asked for, the session is over.
      endForInactivity();
      setLoading(false);
    } else if (getToken()) {
      authApi.me()
        .then((u) => { if (active) setUser(u); })
        .catch(() => { setToken(null); })
        .finally(() => { if (active) setLoading(false); });
    } else {
      setLoading(false);
    }
    return () => { active = false; };
  }, [endForInactivity]);

  // The backend checks the payment and decides the plan; this only hands it the token and shows the result.
  const applyPendingPayment = useCallback(async (): Promise<string | undefined> => {
    const pending = getPendingPayment();
    if (!pending) return undefined;
    try {
      setUser(await paymentApi.redeem(pending.token));
      clearPendingPayment();
      return `Your ${PLAN_LABELS[pending.plan]} plan is now active.`;
    } catch (err) {
      // A refused payment (used, expired, unknown) will never work; a network problem might, next time.
      if (err instanceof HttpError && err.error.status === 400) clearPendingPayment();
      const why = err instanceof Error ? err.message : 'Please try again.';
      return `We could not apply your ${PLAN_LABELS[pending.plan]} payment: ${why} Please contact support.`;
    }
  }, []);

  /** A new session: its inactivity clock starts now and the "logged out for inactivity" message is no longer true. */
  const beginSession = useCallback((token: string) => {
    writeInactivityNotice(false);
    setInactivityLogout(false);
    startActivityClock();
    setToken(token);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password });
    beginSession(res.token);
    setUser(res.user);
    return applyPendingPayment();
  }, [applyPendingPayment, beginSession]);

  const register = useCallback(async (input: RegisterInput) => {
    const res = await authApi.register(input);
    beginSession(res.token);
    setUser(res.user);
    return applyPendingPayment();
  }, [applyPendingPayment, beginSession]);

  const startSession = useCallback((session: AuthResponse) => {
    setToken(session.token);
    setUser(session.user);
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, login, register, applyPendingPayment, updateUser: setUser, startSession, logout, inactivityLogout }),
    [user, loading, login, register, applyPendingPayment, startSession, logout, inactivityLogout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
