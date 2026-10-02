import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { getToken, HttpError, onSessionEnded, setToken } from '../api/client';
import { authApi, paymentApi } from '../api/endpoints';
import type { AuthResponse, RegisterInput, User } from '../api/types';
import { clearPendingPayment, getPendingPayment } from '../lib/checkout';
import { PLAN_LABELS } from '../lib/format';

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
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    onSessionEnded(() => setUser(null));
  }, []);

  useEffect(() => {
    let active = true;
    if (getToken()) {
      authApi.me()
        .then((u) => { if (active) setUser(u); })
        .catch(() => { setToken(null); })
        .finally(() => { if (active) setLoading(false); });
    } else {
      setLoading(false);
    }
    return () => { active = false; };
  }, []);

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

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password });
    setToken(res.token);
    setUser(res.user);
    return applyPendingPayment();
  }, [applyPendingPayment]);

  const register = useCallback(async (input: RegisterInput) => {
    const res = await authApi.register(input);
    setToken(res.token);
    setUser(res.user);
    return applyPendingPayment();
  }, [applyPendingPayment]);

  const startSession = useCallback((session: AuthResponse) => {
    setToken(session.token);
    setUser(session.user);
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, login, register, applyPendingPayment, updateUser: setUser, startSession, logout }),
    [user, loading, login, register, applyPendingPayment, startSession, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
