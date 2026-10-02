import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { JobMonitoring, JobsPanel } from '../components/JobsPanel';
import { errorMessage } from '../components/ui';
import { formatDate, lastDay, NO_ACTIVE_SUBSCRIPTION_MESSAGE, PLAN_LABELS, supportHighlights } from '../lib/format';

/**
 * The customer's own account ("My Profile"): their details (the Account ID is shown, never editable) and what their plan
 * includes. Changing the password is a dialog from the Account menu, and the jobs have their own page. Nothing here
 * changes the plan or the handoff prefix — support and payment do that.
 */
export function AccountPage() {
  const { user } = useAuth();
  if (!user) return null;   // the route is only reachable when signed in
  return (
    <div className="stack page-narrow">
      <div>
        <h1>My Profile</h1>
        <p className="muted">Your details and your plan.</p>
      </div>
      <ProfileCard />
      <SubscriptionCard />
    </div>
  );
}

/** The customer's latest job runs: the monitoring table, on a page of its own so it stays easy to find however many jobs there are. */
export function JobsHistoryPage() {
  return (
    <div className="stack page-narrow">
      <JobMonitoring />
    </div>
  );
}

/** The customer's automatic emails: the existing job scheduler, on a page of its own. */
export function JobsPage() {
  return (
    <div className="stack page-narrow">
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
          Your {PLAN_LABELS[sub.plan]} plan has ended. Until you renew, you cannot start or duplicate a handoff, send a
          draft, import from a file or use HandoffCheck, and its support features are paused. Your existing handoffs,
          returns and PDFs stay available. <Link to={`/checkout?plan=${sub.plan}`}>Renew it</Link> to get everything back.
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
