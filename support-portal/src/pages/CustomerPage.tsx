import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { supportApi } from '../api/endpoints';
import type { SubscriptionPlan } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, ErrorNotice, PlanBadge, PriorityBadge, Spinner, StatusBadge, SubscriptionStatusBadge } from '../components/ui';
import {
  describeChange, describePrice, describeValidity, formatDate, formatDateTime, lastDay, PLAN_LABELS, PLANS, shortPrice,
} from '../lib/format';

/** Letters only, upper case, 2-5 long — the same rule the backend enforces (and decides). */
const PREFIX_PATTERN = /^[A-Z]{2,5}$/;

type PlanStep = 'closed' | 'choose' | 'confirm';

const planName = (plan: string) => PLAN_LABELS[plan as SubscriptionPlan] ?? plan;

/**
 * One customer: who they are, their plan (changed explicitly, with a confirmation), their handoff prefix, and
 * the recorded history of those changes. What the plan allows follows from the plan alone — there is no switch
 * for individual features.
 */
export function CustomerPage() {
  const { accountId = '' } = useParams();
  const { staff, can } = useAuth();
  const manage = can('MANAGE_CUSTOMERS');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const queryKey = ['customer', accountId];
  const profile = useQuery({ queryKey, queryFn: () => supportApi.customer(accountId) });
  // List prices, so staff see what each plan costs. If they cannot be loaded the page simply shows no amounts.
  const prices = useQuery({ queryKey: ['plan-prices'], queryFn: supportApi.planPrices, staleTime: Infinity });
  const priceOf = (plan: SubscriptionPlan) => prices.data?.find((p) => p.plan === plan);

  const [draft, setDraft] = useState<string | null>(null);   // null = showing the saved prefix
  const [planStep, setPlanStep] = useState<PlanStep>('closed');
  const [newPlan, setNewPlan] = useState<SubscriptionPlan | ''>('');
  const [reason, setReason] = useState('');
  const [validUntil, setValidUntil] = useState('');   // yyyy-mm-dd, empty = the plan's own duration from today
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  if (profile.isLoading) return <Spinner />;
  if (profile.isError) return <ErrorNotice error={profile.error} />;
  const { customer, subscription, entitlements, nextHandoffReference, openTickets, recentTickets, recentChanges,
    subscriptionHistory } = profile.data!;
  const sameAsCurrent = newPlan === customer.plan;   // keeping the plan and changing only how long it is paid for
  const prefix = draft ?? customer.handoffPrefix;
  const prefixChanged = prefix !== customer.handoffPrefix;

  async function savePrefix(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSaved(null);
    setBusy(true);
    try {
      const updated = await supportApi.setPrefix(accountId, prefix);
      queryClient.setQueryData(queryKey, updated);
      setDraft(null);
      setSaved(`Prefix changed. This customer's next handoff will be ${updated.nextHandoffReference}; existing handoffs keep the references they already have.`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function closePlanFlow() {
    setPlanStep('closed');
    setNewPlan('');
    setReason('');
    setValidUntil('');
  }

  async function confirmPlanChange() {
    if (!newPlan) return;
    setError(null);
    setSaved(null);
    setBusy(true);
    try {
      const updated = await supportApi.changePlan(accountId, customer.plan, newPlan, reason, validUntil);
      queryClient.setQueryData(queryKey, updated);
      // The backend works out the end when none was typed, so say what it came to.
      const paidUntil = updated.subscription.validUntil ? `, paid until ${lastDay(updated.subscription.validUntil)}` : '';
      setSaved(newPlan === customer.plan
        ? `The ${planName(newPlan)} plan is now paid until ${validUntil}. What the customer can use has changed with it.`
        : `Plan changed from ${customer.plan ? planName(customer.plan) : 'no plan'} to ${planName(newPlan)}${paidUntil}. What the customer can use has changed with it.`);
      closePlanFlow();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <div>
        <Link to="/customers" className="small">← Customers</Link>
        <div className="spread">
          <h1>{customer.name}</h1>
          <div className="row">
            <PlanBadge plan={customer.plan} />
            {subscription.plan && <SubscriptionStatusBadge status={subscription.status} />}
          </div>
        </div>
        <p className="muted">{customer.accountCode}</p>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}
      {saved && <div className="notice notice-success" role="status">{saved}</div>}

      <div className="two-col">
        <div className="card">
          <h2>Profile</h2>
          <dl className="kv">
            <dt>Account ID</dt><dd>{customer.accountCode}</dd>
            <dt>Name</dt><dd>{customer.name}</dd>
            <dt>Email</dt><dd><a href={`mailto:${customer.email}`}>{customer.email}</a></dd>
            <dt>Phone</dt><dd>{customer.phone ? <a href={`tel:${customer.phone.replace(/[^\d+]/g, '')}`}>{customer.phone}</a> : '—'}</dd>
            <dt>Customer since</dt><dd>{formatDate(customer.createdAt)}</dd>
            <dt>Current plan</dt>
            <dd>
              <div className="row">
                <PlanBadge plan={customer.plan} />
                {customer.plan && priceOf(customer.plan) && <span className="small muted">{describePrice(priceOf(customer.plan)!)}</span>}
                {manage && planStep === 'closed' && (
                  <button type="button" className="btn btn-sm" onClick={() => { setError(null); setSaved(null); setPlanStep('choose'); }}>
                    {customer.plan ? 'Change plan' : 'Activate plan'}
                  </button>
                )}
              </div>
            </dd>
            <dt>Subscription</dt>
            <dd>
              {subscription.plan ? (
                <>
                  <div className="row">
                    <SubscriptionStatusBadge status={subscription.status} />
                    <span className="small">{describeValidity(subscription.validUntil)}</span>
                  </div>
                  <div className="small muted">
                    {subscription.startedAt ? `Since ${formatDate(subscription.startedAt)}` : 'Start date not recorded'}
                  </div>
                  {subscription.status === 'INACTIVE' && (
                    <div className="small remaining-open">
                      Expired: the {planName(subscription.plan)} plan's support features are paused until it is renewed.
                    </div>
                  )}
                </>
              ) : (
                <>
                  <div className="small">No active plan</div>
                  <div className="small remaining-open">
                    Nothing has been activated on this account yet. The customer can sign in and open a ticket to ask for it;
                    giving the account a plan switches everything on.
                  </div>
                </>
              )}
            </dd>
            <dt>Includes</dt>
            <dd>
              <Entitlement on={entitlements.contactSupport} label="Contact Support in the app" />
              <Entitlement on={entitlements.message} label="Support messages" />
              <Entitlement on={entitlements.ticket} label="Support tickets" />
              <Entitlement on={entitlements.call} label="Phone support" />
            </dd>
            <dt>Support priority</dt><dd><PriorityBadge priority={entitlements.priority} /></dd>
          </dl>
          <p className="small muted mt-2">
            What a customer can use follows from their plan alone; individual features cannot be switched on or off.
          </p>

          {planStep === 'choose' && (
            <div className="panel mt-2">
              <h3>Change plan</h3>
              <div className="field">
                <label htmlFor="newPlan">New plan</label>
                <select id="newPlan" value={newPlan} onChange={(e) => setNewPlan(e.target.value as SubscriptionPlan)}>
                  <option value="" disabled>Choose a plan…</option>
                  {PLANS.map((p) => (
                    <option key={p} value={p}>
                      {PLAN_LABELS[p]}{p === customer.plan ? ' (current — change how long it is paid for)'
                        : priceOf(p) ? ` — ${shortPrice(priceOf(p)!)}` : ''}
                    </option>
                  ))}
                </select>
                {newPlan && !sameAsCurrent && priceOf(newPlan) && (
                  <p className="small mt-1">
                    Charge for <strong>{PLAN_LABELS[newPlan]}</strong>: <strong>{describePrice(priceOf(newPlan)!)}</strong>
                  </p>
                )}
              </div>
              <div className="field">
                <label htmlFor="validUntil">
                  Last day of the plan {sameAsCurrent ? '' : <span className="muted">(optional — empty: the plan's own duration, counted from today)</span>}
                </label>
                <input id="validUntil" type="date" value={validUntil} min={new Date().toISOString().slice(0, 10)}
                       onChange={(e) => setValidUntil(e.target.value)} />
                <p className="small muted mt-1">
                  After this day the plan counts as expired and its support features pause until it is renewed. The
                  core product is unaffected.
                </p>
              </div>
              <div className="field">
                <label htmlFor="reason">Reason <span className="muted">(optional, kept in the history)</span></label>
                <textarea id="reason" rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)}
                          placeholder="e.g. Customer upgraded after payment" />
              </div>
              <div className="row">
                <button type="button" className="btn btn-primary" disabled={!newPlan || (sameAsCurrent && !validUntil)}
                        onClick={() => setPlanStep('confirm')}>
                  Review change
                </button>
                <button type="button" className="btn btn-ghost" onClick={closePlanFlow}>Cancel</button>
              </div>
            </div>
          )}

          {planStep === 'confirm' && newPlan && (
            <div className="panel panel-warning mt-2" role="alertdialog" aria-label="Confirm plan change">
              <h3>Confirm plan change</h3>
              <p>
                {sameAsCurrent ? (
                  <>Keep <strong>{customer.name}</strong> ({customer.accountCode}) on the <strong>{planName(newPlan)}</strong> plan
                    {' '}but paid until <strong>{validUntil}</strong>?</>
                ) : (
                  <>{customer.plan ? 'Change' : 'Activate'} <strong>{customer.name}</strong> ({customer.accountCode}) from{' '}
                    {customer.plan ? <strong>{planName(customer.plan)}</strong> : 'no plan'} to <strong>{planName(newPlan)}</strong>
                    {validUntil ? <>, paid until <strong>{validUntil}</strong></> : ", for the plan's own duration starting today"}?</>
                )}
              </p>
              {!sameAsCurrent && priceOf(newPlan) && (
                <p>
                  Amount to charge for the new plan: <strong>{describePrice(priceOf(newPlan)!)}</strong>. HandOffly does
                  not take payment — collect it separately.
                </p>
              )}
              <p className="small">
                What the customer can use changes immediately. This is recorded in the history as a change made by you
                ({staff?.staffCode}). It does not record or prove a payment.
              </p>
              {reason.trim() && <p className="small">Reason: “{reason.trim()}”</p>}
              <div className="row">
                <button type="button" className="btn btn-primary" disabled={busy} onClick={confirmPlanChange}>
                  {busy ? 'Changing…' : 'Confirm change'}
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => setPlanStep('choose')}>Back</button>
              </div>
            </div>
          )}
        </div>

        {manage && <form className="card" onSubmit={savePrefix}>
          <h2>Handoff reference prefix</h2>
          <p className="small muted">
            New handoffs for this customer are numbered with this prefix, counting up from where they are now
            (next: <strong>{nextHandoffReference}</strong>). Existing handoffs keep the reference they were given.
          </p>
          <div className="field">
            <label htmlFor="prefix">Prefix <span className="muted">(2–5 capital letters)</span></label>
            <input id="prefix" value={prefix} maxLength={5} autoComplete="off" spellCheck={false}
                   onChange={(e) => { setSaved(null); setDraft(e.target.value.toUpperCase().replace(/[^A-Z]/g, '')); }} />
          </div>
          <button className="btn btn-primary" disabled={busy || !prefixChanged || !PREFIX_PATTERN.test(prefix)}>
            {busy ? 'Saving…' : 'Change prefix'}
          </button>
        </form>}
      </div>

      <div className="card">
        <h2>Subscription history</h2>
        {subscriptionHistory.length === 0 ? (
          <p className="muted">No plan changes are on record for this customer.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Changed</th><th>Plan</th><th>Paid</th><th>By</th><th>Reason</th></tr></thead>
              <tbody>
                {subscriptionHistory.map((h, i) => (
                  <tr key={i}>
                    <td className="small muted">{formatDateTime(h.changedAt)}</td>
                    <td>
                      <strong>{h.previousPlan ? `${planName(h.previousPlan)} → ` : ''}{planName(h.newPlan)}</strong>
                    </td>
                    <td className="small">
                      {h.startsAt ? `From ${formatDate(h.startsAt)}` : ''}
                      <div className="muted">{describeValidity(h.validUntil)}</div>
                    </td>
                    <td className="small">
                      {h.source === 'PAYMENT' ? 'Payment' : <>{h.staffCode}<div className="muted">{h.staffName}</div></>}
                    </td>
                    <td className="small">{h.reason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h2>Recent changes</h2>
        {recentChanges.length === 0 ? <p className="muted">No plan or prefix changes have been made.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>When</th><th>Change</th><th>By</th><th>Reason</th></tr></thead>
              <tbody>
                {recentChanges.map((c, i) => (
                  <tr key={i}>
                    <td className="small muted">{formatDateTime(c.at)}</td>
                    <td><strong>{describeChange(c)}</strong></td>
                    <td className="small">{c.staffCode}<div className="muted">{c.staffName}</div></td>
                    <td className="small">{c.reason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Tickets <span className="badge">{openTickets} open</span></h2>
          <Link to={`/tickets?account=${customer.accountCode}&status=ALL`} className="small">View all tickets</Link>
        </div>
        {recentTickets.length === 0 ? <p className="muted">No tickets yet.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Ticket</th><th>Subject</th><th>Status</th><th>Updated</th></tr></thead>
              <tbody>
                {recentTickets.map((t) => (
                  <tr key={t.ticketCode} className="clickable" onClick={() => navigate(`/tickets/${t.ticketCode}`)}>
                    <td className="small">{t.ticketCode}</td>
                    <td><strong>{t.subject}</strong></td>
                    <td><StatusBadge status={t.status} /></td>
                    <td className="small muted">{formatDateTime(t.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function Entitlement({ on, label }: { on: boolean; label: string }) {
  return <div className={on ? '' : 'muted'}>{on ? '✓' : '✗'} {label}</div>;
}
