import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { AuthShell } from '../components/AuthShell';
import { errorMessage } from '../components/ui';

/**
 * Asking for a password reset link. The answer is the same whether or not an account has this email, so the
 * page can never be used to find out who is registered.
 */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await authApi.forgotPassword(email.trim());
      setSent(true);
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
        <p className="muted center mb-2">Forgot your password?</p>
        {sent ? (
          <>
            <div className="notice notice-success mb-2" role="status">
              If an account exists for that email, we have sent a link to choose a new password. It works once and
              expires soon. Check your spam folder if it does not arrive.
            </div>
            <Link to="/login" className="btn btn-block">Back to sign in</Link>
          </>
        ) : (
          <>
            <p className="small muted">Enter your account email and we will send you a link to choose a new one.</p>
            {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
            <div className="field">
              <label htmlFor="email">Email</label>
              <input id="email" type="email" autoComplete="username" value={email}
                     onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? 'Sending…' : 'Send reset link'}
            </button>
            <p className="center small mt-3 muted"><Link to="/login">← Back to sign in</Link></p>
          </>
        )}
      </form>
    </AuthShell>
  );
}
