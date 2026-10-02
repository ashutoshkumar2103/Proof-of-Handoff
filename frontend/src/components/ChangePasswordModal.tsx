import { useEffect, useRef, useState } from 'react';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { PasswordInput } from './PasswordInput';
import { errorMessage } from './ui';

/**
 * Changing the signed-in customer's password, in the app's modal. This is the Account page's former "Change password" card
 * moved as it was: the same fields and checks, the same request, and on success every other session ends while this one
 * carries on. The backend still decides what a valid password is.
 */
export function ChangePasswordModal({ onClose }: { onClose: () => void }) {
  const { startSession } = useAuth();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const firstField = useRef<HTMLDivElement>(null);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setDone(false);
    setForm({ ...form, [k]: e.target.value });
  };

  useEffect(() => { firstField.current?.querySelector('input')?.focus(); }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function onChange(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setDone(false);
    if (form.newPassword !== form.confirmPassword) {
      setError('The new password and its confirmation do not match.');
      return;
    }
    setBusy(true);
    try {
      startSession(await authApi.changePassword(form));   // every other session has ended; this one carries on
      setForm({ currentPassword: '', newPassword: '', confirmPassword: '' });
      setDone(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  // Not closed by a click outside: three typed passwords are not worth losing to a stray click.
  return (
    <div className="modal-overlay">
      <form className="modal" role="dialog" aria-modal="true" aria-labelledby="change-password-title" onSubmit={onChange}>
        <h3 className="modal-title" id="change-password-title">Change password</h3>
        {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
        {done && (
          <div className="notice notice-success mb-2" role="status">
            Your password was changed. You were signed out of your other devices.
          </div>
        )}
        <div className="field" ref={firstField}>
          <label htmlFor="currentPassword">Old password</label>
          <PasswordInput id="currentPassword" autoComplete="current-password"
                         value={form.currentPassword} onChange={set('currentPassword')} required />
        </div>
        <div className="field">
          <label htmlFor="newPassword">New password <span className="muted">(min 8 characters)</span></label>
          <PasswordInput id="newPassword" autoComplete="new-password" minLength={8}
                         value={form.newPassword} onChange={set('newPassword')} required />
        </div>
        <div className="field">
          <label htmlFor="confirmPassword">Re-enter new password</label>
          <PasswordInput id="confirmPassword" autoComplete="new-password" minLength={8}
                         value={form.confirmPassword} onChange={set('confirmPassword')} required />
        </div>
        <p className="small muted">Forgot your old password? Sign out and use <em>Forgot password?</em> on the sign-in page.</p>
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{done ? 'Close' : 'Cancel'}</button>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Changing…' : 'Change password'}</button>
        </div>
      </form>
    </div>
  );
}
