import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { INACTIVITY_LOGOUT_MESSAGE } from '../auth/inactivity';
import { Icon } from '../components/Icon';
import { Logo } from '../components/Logo';
import { errorMessage, Spinner } from '../components/ui';

export function LoginPage() {
  const { user, loading, login, inactivityLogout } = useAuth();
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (loading) return <div className="boot"><Spinner /></div>;
  if (user) return <Navigate to={from} replace />;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <main className="auth-page">
      <form className="card auth-card" onSubmit={onSubmit}>
        <div className="brand brand-lg"><Logo variant="full" /></div>
        <p className="muted center">Sign in to give, track and receive back.</p>
        {inactivityLogout && <div className="notice notice-warning" role="status">{INACTIVITY_LOGOUT_MESSAGE}</div>}
        {error && <div className="notice notice-error" role="alert">{error}</div>}
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" inputMode="email" autoComplete="username" autoCapitalize="none" value={email}
                 onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          {/* The same full-width box as the email; the eye sits inside its right edge. */}
          <div className="input-icon">
            <input id="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password}
                   onChange={(e) => setPassword(e.target.value)} required />
            <button type="button" className="input-icon-btn" aria-label={showPassword ? 'Hide password' : 'Show password'}
                    aria-pressed={showPassword} onClick={() => setShowPassword((s) => !s)}>
              <Icon name={showPassword ? 'eye-off' : 'eye'} />
            </button>
          </div>
        </div>
        <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <p className="small muted center">New to HandOffly? Create your account on the HandOffly website, then sign in here.</p>
      </form>
    </main>
  );
}
