import { useState } from 'react';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { useConfirm } from '../components/ConfirmSheet';
import { errorMessage, Toast } from '../components/ui';
import { formatDate, lastDay, NO_ACTIVE_SUBSCRIPTION_MESSAGE, PLAN_LABELS } from '../lib/format';
import { getThemeChoice, setThemeChoice } from '../lib/theme';
import type { ThemeChoice } from '../lib/theme';
import { useTransient } from '../lib/useTransient';

/** The customer's own account: details, plan, appearance, password, and signing out. The plan itself is changed by payment or support, not here. */
export function AccountPage() {
  const { user, logout } = useAuth();
  const confirm = useConfirm();
  if (!user) return null;   // the route is only reachable when signed in

  async function onLogout() {
    const res = await confirm({ title: 'Sign out?', message: 'You will need to sign in again to see your handoffs.', confirmText: 'Sign out' });
    if (res.confirmed) logout();
  }

  return (
    <div className="stack">
      <h1>Account</h1>
      <SubscriptionCard />
      <ProfileCard />
      <PasswordCard />
      <AppearanceCard />
      <button className="btn btn-danger btn-block" onClick={() => void onLogout()}>Sign out</button>
    </div>
  );
}

function SubscriptionCard() {
  const { user } = useAuth();
  const sub = user!.subscription;
  if (!sub.plan) {
    return (
      <section className="card">
        <div className="spread"><h2>Subscription</h2><span className="badge badge-danger badge-dot">Not activated</span></div>
        <div className="notice notice-warning">{NO_ACTIVE_SUBSCRIPTION_MESSAGE}</div>
      </section>
    );
  }
  const lapsed = sub.status === 'INACTIVE';
  return (
    <section className="card">
      <div className="spread">
        <h2>Subscription</h2>
        <span className={`badge badge-dot ${lapsed ? 'badge-danger' : 'badge-success'}`}>{lapsed ? 'Expired' : 'Active'}</span>
      </div>
      <p><strong>{PLAN_LABELS[sub.plan]}</strong> plan</p>
      <p className="muted small">
        {sub.startedAt ? `Since ${formatDate(sub.startedAt)} · ` : ''}
        {sub.validUntil ? `${lapsed ? 'Ended' : 'Valid until'} ${lastDay(sub.validUntil)}` : 'No end date'}
      </p>
      {lapsed && (
        <div className="notice notice-warning">
          Your plan has ended. Until you renew it you can’t start or send a handoff. Your existing handoffs, returns and PDFs stay available.
          Renew from the HandOffly website.
        </div>
      )}
    </section>
  );
}

function ProfileCard() {
  const { user, updateUser } = useAuth();
  const [form, setForm] = useState({ displayName: user!.displayName, organization: user!.organization ?? '', phone: user!.phone ?? '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useTransient<string>();
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  async function onSave(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      updateUser(await authApi.updateProfile({ displayName: form.displayName.trim(), organization: form.organization.trim(), phone: form.phone.trim() }));
      setSaved('Your details were saved.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={onSave}>
      <h2>Your details</h2>
      {error && <div className="notice notice-error" role="alert">{error}</div>}
      <div className="field"><label htmlFor="code">Account ID</label><input id="code" className="readonly" value={user!.accountCode} readOnly /></div>
      <div className="field"><label htmlFor="email">Email (you sign in with it)</label><input id="email" className="readonly" value={user!.email} readOnly /></div>
      <div className="field"><label htmlFor="displayName">Your name</label><input id="displayName" value={form.displayName} maxLength={150} onChange={set('displayName')} required /></div>
      <div className="field"><label htmlFor="organization">Organization <span className="muted">(optional)</span></label><input id="organization" value={form.organization} maxLength={200} onChange={set('organization')} /></div>
      <div className="field"><label htmlFor="phone">Phone <span className="muted">(optional)</span></label><input id="phone" type="tel" inputMode="tel" value={form.phone} maxLength={40} onChange={set('phone')} /></div>
      <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Saving…' : 'Save details'}</button>
      {saved && <Toast tone="success">{saved}</Toast>}
    </form>
  );
}

function PasswordCard() {
  const { startSession } = useAuth();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useTransient<string>();
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  async function onChange(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (form.newPassword !== form.confirmPassword) { setError('The new password and its confirmation do not match.'); return; }
    setBusy(true);
    try {
      startSession(await authApi.changePassword(form));   // every other session has ended; this one carries on
      setForm({ currentPassword: '', newPassword: '', confirmPassword: '' });
      setDone('Your password was changed. You were signed out of your other devices.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={onChange}>
      <h2>Change password</h2>
      {error && <div className="notice notice-error" role="alert">{error}</div>}
      <div className="field"><label htmlFor="cur">Current password</label><input id="cur" type="password" autoComplete="current-password" value={form.currentPassword} onChange={set('currentPassword')} required /></div>
      <div className="field"><label htmlFor="new">New password</label><input id="new" type="password" autoComplete="new-password" value={form.newPassword} onChange={set('newPassword')} required /></div>
      <div className="field"><label htmlFor="conf">Confirm new password</label><input id="conf" type="password" autoComplete="new-password" value={form.confirmPassword} onChange={set('confirmPassword')} required /></div>
      <button className="btn btn-block" disabled={busy}>{busy ? 'Changing…' : 'Change password'}</button>
      {done && <Toast tone="success">{done}</Toast>}
    </form>
  );
}

const THEMES: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'Match phone' }, { value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' },
];

function AppearanceCard() {
  const [choice, setChoice] = useState<ThemeChoice>(getThemeChoice);
  return (
    <fieldset className="card">
      <legend className="h2">Appearance</legend>
      <div className="segmented" role="radiogroup" aria-label="Theme">
        {THEMES.map((t) => (
          <label key={t.value} className={choice === t.value ? 'active' : ''}>
            <input type="radio" name="theme" value={t.value} checked={choice === t.value}
                   onChange={() => { setChoice(t.value); setThemeChoice(t.value); }} />
            {t.label}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
