import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { JobsPanel } from '../components/JobsPanel';
import { PasswordInput } from '../components/PasswordInput';
import { errorMessage } from '../components/ui';
import { formatDate, lastDay, NO_ACTIVE_SUBSCRIPTION_MESSAGE, PLAN_LABELS, supportHighlights } from '../lib/format';

/**
 * The customer's own account: their details (the Account ID is shown, never editable), what their plan
 * includes, and changing their password. Nothing here changes the plan or the handoff prefix — support and
 * payment do that.
 */
export function AccountPage() {
  const { user } = useAuth();
  if (!user) return null;   // the route is only reachable when signed in
  return (
    <div className="stack page-narrow">
      <div>
        <h1>Account</h1>
        <p className="muted">Your details, your plan, your password and your automatic emails.</p>
      </div>
      <ProfileCard />
      <SubscriptionCard />
      <PasswordCard />
      <JobsPanel />
    </div>
  );
}

function ProfileCard() {
  const { user, updateUser } = useAuth();
  const [form, setForm] = useState({
    displayName: user!.displayName, organization: user!.organization ?? '', phone: user!.phone ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setSaved(false);
    setForm({ ...form, [k]: e.target.value });
  };

  async function onSave(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSaved(false);
    setBusy(true);
    try {
      updateUser(await authApi.updateProfile({
        displayName: form.displayName, organization: form.organization, phone: form.phone,
      }));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={onSave}>
      <h2>Account information</h2>
      {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      {saved && <div className="notice notice-success mb-2" role="status">Your details were saved.</div>}
      <div className="field-row">
        <div className="field">
          <label htmlFor="accountCode">Account ID</label>
          <input id="accountCode" className="readonly-field" value={user!.accountCode} readOnly />
        </div>
        <div className="field">
          <label htmlFor="handoffPrefix">Handoff reference prefix</label>
          <input id="handoffPrefix" className="readonly-field" value={user!.handoffPrefix} readOnly />
        </div>
      </div>
      <p className="small muted" style={{ marginTop: '-0.4rem' }}>
        Quote your Account ID when you contact support. The prefix is set by support: your handoffs are numbered
        {' '}{user!.handoffPrefix}-1, {user!.handoffPrefix}-2 …
      </p>
      <div className="field-row">
        <div className="field">
          <label htmlFor="displayName">Your name</label>
          <input id="displayName" value={form.displayName} maxLength={150} onChange={set('displayName')} required />
        </div>
        <div className="field">
          <label htmlFor="organization">Organization <span className="muted">(optional)</span></label>
          <input id="organization" value={form.organization} maxLength={200} onChange={set('organization')} />
        </div>
      </div>
      <div className="field-row">
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" className="readonly-field" value={user!.email} readOnly />
        </div>
        <div className="field">
          <label htmlFor="phone">Phone <span className="muted">(optional)</span></label>
          <input id="phone" type="tel" value={form.phone} maxLength={40} onChange={set('phone')} />
        </div>
      </div>
      <p className="small muted" style={{ marginTop: '-0.4rem' }}>Your email is how you sign in, so it cannot be changed here.</p>
      <button className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save details'}</button>
    </form>
  );
}

function SubscriptionCard() {
  const { user } = useAuth();
  const sub = user!.subscription;
  if (!sub.plan) {   // nothing has ever been activated on this account: not a plan that ended
    return (
      <div className="card">
        <h2>Subscription</h2>
        <div className="row">
          <p style={{ margin: 0 }}><strong>No active plan</strong></p>
          <span className="badge badge-dot badge-danger">Not activated</span>
        </div>
        <div className="notice notice-warning mb-2" style={{ marginTop: '0.6rem' }}>
          {NO_ACTIVE_SUBSCRIPTION_MESSAGE}{' '}
          <Link to="/support">Contact Support</Link> or <Link to="/support/new">create a ticket</Link>.
        </div>
        <p className="small muted">
          You can also <Link to="/#pricing">choose a plan on the pricing page</Link> and pay for it to activate this account yourself.
        </p>
      </div>
    );
  }
  const lapsed = sub.status === 'INACTIVE';
  return (
    <div className="card">
      <h2>Subscription</h2>
      <div className="row">
        <p style={{ margin: 0 }}><strong>{PLAN_LABELS[sub.plan]}</strong> plan</p>
        <span className={`badge badge-dot ${lapsed ? 'badge-danger' : 'badge-success'}`}>{lapsed ? 'Expired' : 'Active'}</span>
      </div>
      <p className="small muted" style={{ marginTop: '0.4rem' }}>
        {sub.startedAt ? `Since ${formatDate(sub.startedAt)} · ` : ''}
        {sub.validUntil ? `${lapsed ? 'Ended' : 'Valid until'} ${lastDay(sub.validUntil)}` : 'No end date'}
      </p>
      {lapsed && (
        <div className="notice notice-warning mb-2">
          Your {PLAN_LABELS[sub.plan]} plan has ended, so its support features are paused. Your handoffs and everything
          else in HandOffly keep working. <Link to={`/checkout?plan=${sub.plan}`}>Renew it</Link> to get them back.
        </div>
      )}
      <ul className="price-features">
        {supportHighlights(user!.support).map((line) => <li key={line}>{line}</li>)}
      </ul>
      <p className="small muted">
        To change your plan, <Link to="/#pricing">choose one on the pricing page</Link>. Handoffs, returns and PDFs are
        the same on every plan; HandoffCheck comes with Half-Yearly and Yearly.
      </p>
    </div>
  );
}

function PasswordCard() {
  const { startSession } = useAuth();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setDone(false);
    setForm({ ...form, [k]: e.target.value });
  };

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

  return (
    <form className="card" onSubmit={onChange}>
      <h2>Change password</h2>
      {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      {done && (
        <div className="notice notice-success mb-2" role="status">
          Your password was changed. You were signed out of your other devices.
        </div>
      )}
      <div className="field">
        <label htmlFor="currentPassword">Current password</label>
        <PasswordInput id="currentPassword" autoComplete="current-password"
                       value={form.currentPassword} onChange={set('currentPassword')} required />
      </div>
      <div className="field-row">
        <div className="field">
          <label htmlFor="newPassword">New password <span className="muted">(min 8 characters)</span></label>
          <PasswordInput id="newPassword" autoComplete="new-password" minLength={8}
                         value={form.newPassword} onChange={set('newPassword')} required />
        </div>
        <div className="field">
          <label htmlFor="confirmPassword">Confirm new password</label>
          <PasswordInput id="confirmPassword" autoComplete="new-password" minLength={8}
                         value={form.confirmPassword} onChange={set('confirmPassword')} required />
        </div>
      </div>
      <button className="btn btn-primary" disabled={busy}>{busy ? 'Changing…' : 'Change password'}</button>
      <p className="small muted mt-2">
        Forgot your current password? Sign out and use <em>Forgot password?</em> on the sign-in page.
      </p>
    </form>
  );
}
