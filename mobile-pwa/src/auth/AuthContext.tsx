import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getToken, isNetworkError, onSessionEnded, setToken } from '../api/client';
import { authApi } from '../api/endpoints';
import type { AuthResponse, User } from '../api/types';
import { hasIdleExpired, readInactivityNotice, startActivityClock, useInactivityLogout, writeInactivityNotice } from './inactivity';

interface AuthState {
  user: User | null;
  /** True until the first answer about a stored session. */
  loading: boolean;
  /** A stored session could not be checked because HandOffly could not be reached (not because it ended): the person is not signed out. */
  unreachable: boolean;
  /** Asks about the stored session again, after `unreachable`. */
  retry: () => void;
  login: (email: string, password: string) => Promise<void>;
  /** Shows an edited account straight away (the backend's answer after a profile change). */
  updateUser: (user: User) => void;
  /** Takes over a fresh session (after a password change, which ends every other session). */
  startSession: (session: AuthResponse) => void;
  logout: () => void;
  /** The person was last signed out for inactivity: the sign-in page says so, until they sign in again. */
  inactivityLogout: boolean;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [unreachable, setUnreachable] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [inactivityLogout, setInactivityLogout] = useState(readInactivityNotice);

  // Whoever signs out, or whoever the backend stops accepting, leaves nothing of their data behind for the next person on this phone.
  const endSession = useCallback(() => {
    setToken(null);
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => {
    onSessionEnded(() => {
      endSession();
      setInactivityLogout(readInactivityNotice());
    });
  }, [endSession]);

  // 15 minutes without interaction: the notice first, so that the other windows (told by the token going away) can read it.
  const endForInactivity = useCallback(() => {
    writeInactivityNotice(true);
    setInactivityLogout(true);
    endSession();
  }, [endSession]);
  const reportActivity = useCallback(() => {
    // Only renews a session that is still there: an answer arriving after sign-out must not bring it back.
    authApi.activity().then((renewed) => { if (getToken()) setToken(renewed.token); }).catch(() => { /* a refusal already ended the session */ });
  }, []);
  useInactivityLogout(user !== null, endForInactivity, reportActivity);

  useEffect(() => {
    let active = true;
    if (getToken() && hasIdleExpired()) {
      // The app was left (closed, in the background) for 15 minutes or more: no data is asked for, the session is over.
      endForInactivity();
      setLoading(false);
    } else if (getToken()) {
      setLoading(true);
      setUnreachable(false);
      authApi.me()
        .then((u) => { if (active) setUser(u); })
        // A 401 already ended the session. If HandOffly just cannot be reached the session may be fine: say so rather than sign out.
        .catch((err) => { if (active && isNetworkError(err)) setUnreachable(true); })
        .finally(() => { if (active) setLoading(false); });
    } else {
      setLoading(false);
    }
    return () => { active = false; };
  }, [attempt, endForInactivity]);

  // The plan can end, or support can renew it, while the app sits in the background: ask again when it comes back to the front.
  useEffect(() => {
    if (!user) return;
    const refresh = () => {
      if (document.visibilityState !== 'visible') return;
      authApi.me().then(setUser).catch(() => { /* offline or refused: keep what is shown; the backend judges every request anyway */ });
    };
    document.addEventListener('visibilitychange', refresh);
    return () => document.removeEventListener('visibilitychange', refresh);
  }, [user?.id]);   // eslint-disable-line react-hooks/exhaustive-deps

  const login = useCallback(async (email: string, password: string) => {
    const res = await authApi.login({ email, password });
    queryClient.clear();
    // A new session: its inactivity clock starts now and the "logged out for inactivity" message is no longer true.
    writeInactivityNotice(false);
    setInactivityLogout(false);
    startActivityClock();
    setToken(res.token);
    setUser(res.user);
  }, [queryClient]);

  const startSession = useCallback((session: AuthResponse) => {
    setToken(session.token);
    setUser(session.user);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, unreachable, retry: () => setAttempt((n) => n + 1), login, updateUser: setUser, startSession, logout: endSession, inactivityLogout }),
    [user, loading, unreachable, login, startSession, endSession, inactivityLogout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
