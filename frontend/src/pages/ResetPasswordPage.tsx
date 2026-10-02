import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { AuthShell } from '../components/AuthShell';
import { PasswordInput } from '../components/PasswordInput';
import { errorMessage } from '../components/ui';

/** Choosing a new password from the emailed link. No current password is asked for: the link is the proof. */
export function ResetPasswordPage() {
  const { token = '' } = useParams();
  const { logout } = useAuth();
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError('The new password and its confirmation do not match.');
      return;
    }
    setBusy(true);
    try {
      await authApi.resetPassword({ token, newPassword, confirmPassword });
      logout();   // a reset ends every session, including one still open in this browser
      setDone(true);
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
        <p className="muted center mb-2">Choose a new password</p>
        {done ? (
          <>
            <div className="notice notice-success mb-2" role="status">
              Your password has been changed. Sign in with the new one.
            </div>
            <Link to="/login" className="btn btn-primary btn-block">Sign in</Link>
          </>
        ) : (
          <>
            {error && (
              <div className="notice notice-error mb-2" role="alert">
                {error} {error.includes('invalid or has expired') && <Link to="/forgot-password">Request a new link</Link>}
              </div>
            )}
            <div className="field">
              <label htmlFor="newPassword">New password <span className="muted">(min 8 characters)</span></label>
              <PasswordInput id="newPassword" autoComplete="new-password" minLength={8}
                             value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required />
            </div>
            <div className="field">
              <label htmlFor="confirmPassword">Confirm new password</label>
              <PasswordInput id="confirmPassword" autoComplete="new-password" minLength={8}
                             value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required />
            </div>
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? 'Saving…' : 'Set new password'}
            </button>
          </>
        )}
      </form>
    </AuthShell>
  );
}
