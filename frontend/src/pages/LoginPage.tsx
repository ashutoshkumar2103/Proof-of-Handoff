import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../components/ui';
import { PasswordInput } from '../components/PasswordInput';
import { AuthShell } from '../components/AuthShell';
import { PendingPaymentNotice } from '../components/PendingPaymentNotice';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const notice = await login(email, password);
      navigate('/dashboard', { state: { notice } });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <form className="card auth-card" onSubmit={onSubmit}>
        <div className="brand">Hand<span>Offly</span></div>
        <p className="muted center mb-2">Welcome back — sign in to continue</p>
        <PendingPaymentNotice action="Sign in" />
        {error &&<div className="notice notice-error mb-2">{error}</div>}
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="username" value={email}
                 onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <PasswordInput id="password" autoComplete="current-password" value={password}
                         onChange={(e) => setPassword(e.target.value)} required />
        </div>
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="center small mt-3 muted">
          No account? <Link to="/register">Create one</Link>
        </p>
      </form>
    </AuthShell>
  );
}
