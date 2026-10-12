import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getToken, isNetworkError, onSessionEnded, setToken } from '../api/client';
import { authApi } from '../api/endpoints';
import type { AuthResponse, User } from '../api/types';

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
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [unreachable, setUnreachable] = useState(false);
  const [attempt, setAttempt] = useState(0);

  // Whoever signs out, or whoever the backend stops accepting, leaves nothing of their data behind for the next person on this phone.
  const endSession = useCallback(() => {
    setToken(null);
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => { onSessionEnded(endSession); }, [endSession]);

  useEffect(() => {
    let active = true;
    if (getToken()) {
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
  }, [attempt]);

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
    setToken(res.token);
    setUser(res.user);
  }, [queryClient]);

  const startSession = useCallback((session: AuthResponse) => {
    setToken(session.token);
    setUser(session.user);
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, unreachable, retry: () => setAttempt((n) => n + 1), login, updateUser: setUser, startSession, logout: endSession }),
    [user, loading, unreachable, login, startSession, endSession]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
