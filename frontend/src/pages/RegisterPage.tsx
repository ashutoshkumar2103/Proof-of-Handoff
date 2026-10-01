import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../components/ui';
import { PasswordInput } from '../components/PasswordInput';
import { AuthShell } from '../components/AuthShell';

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ displayName: '', organization: '', email: '', password: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value });

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await register({
        email: form.email, password: form.password,
        displayName: form.displayName, organization: form.organization || undefined,
      });
      navigate('/dashboard');
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
        <p className="muted center mb-2">Create your account</p>
        {error && <div className="notice notice-error mb-2">{error}</div>}
        <div className="field">
          <label htmlFor="displayName">Your name</label>
          <input id="displayName" value={form.displayName} onChange={set('displayName')} required />
        </div>
        <div className="field">
          <label htmlFor="organization">Organization <span className="muted">(optional)</span></label>
          <input id="organization" value={form.organization} onChange={set('organization')} />
        </div>
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="username" value={form.email} onChange={set('email')} required />
        </div>
        <div className="field">
          <label htmlFor="password">Password <span className="muted">(min 8 characters)</span></label>
          <PasswordInput id="password" autoComplete="new-password" minLength={8}
                         value={form.password} onChange={set('password')} required />
        </div>
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Creating…' : 'Create account'}
        </button>
        <p className="center small mt-3 muted">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </form>
    </AuthShell>
  );
}
