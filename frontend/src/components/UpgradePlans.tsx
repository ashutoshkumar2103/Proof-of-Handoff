import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { HttpError } from '../api/client';
import { paymentApi } from '../api/endpoints';
import type { SubscriptionPlan } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { cardProblem, EMPTY_CARD } from '../lib/checkout';
import type { CardInput } from '../lib/checkout';
import { formatMoney, PLAN_LABELS, supportHighlights } from '../lib/format';
import { DemoCardFields } from './DemoCardFields';
import { ErrorNotice, Spinner, errorMessage } from './ui';

/**
 * The way up from a plan that does not include HandoffCheck, without leaving the page: the plans that include it and cost more than
 * the customer's, each priced as what is left to pay (the plan they are on counts as already paid), and the demo card form to pay it
 * and switch at once. Which plans, and what each costs, is the backend's answer; this only shows it and asks the backend to charge
 * exactly the amount shown. `onUpgraded` is told which plan the customer is now on.
 */
export function UpgradePlans({ onUpgraded }: { onUpgraded: (plan: SubscriptionPlan) => void }) {
  const { user } = useAuth();
  const options = useQuery({
    queryKey: ['upgrade-options'],
    queryFn: paymentApi.upgradeOptions,
    staleTime: 0,
    gcTime: 0,      // every look starts from what the backend says now
    retry: false,
  });
  const [chosen, setChosen] = useState<SubscriptionPlan | null>(null);
  const [card, setCard] = useState<CardInput>(EMPTY_CARD);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only plans that include HandoffCheck matter here; the backend says which they are.
  const choices = options.data?.filter((o) => o.handoffCheck) ?? [];
  const option = choices.find((o) => o.price.plan === chosen);

  function choose(plan: SubscriptionPlan) {
    setError(null);
    setChosen(plan);
  }

  async function onPay(e: React.FormEvent) {
    e.preventDefault();
    if (!option) return;
    setError(null);
    // Checked here first so that a real card number is never sent anywhere; the backend checks again.
    const problem = cardProblem(card);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      await paymentApi.upgrade(option.price.plan, option.amountDue, { cardNumber: card.number, expiry: card.expiry, cvc: card.cvc });
      onUpgraded(option.price.plan);   // stays busy: the page is about to change under this panel
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
      // The plan or its price moved since it was shown: show what it is now.
      if (err instanceof HttpError && err.error.status === 409) void options.refetch();
    }
  }

  return (
    <div className="card">
      <h2>Upgrade to use HandoffCheck</h2>
      <p className="muted small">
        Pay only the difference: what you have already paid for your plan counts towards the new one. The new plan starts today.
      </p>
      {options.isLoading ? <Spinner /> : options.isError ? <ErrorNotice error={options.error} /> : choices.length === 0 ? (
        <p className="muted">There is no plan to upgrade to from here right now.</p>
      ) : (
        <div className="stack">
          {choices.map((o) => {
            const label = PLAN_LABELS[o.price.plan];
            const money = (amount: number) => formatMoney(amount, o.price.currency);
            return (
              <div key={o.price.plan} className="card">
                <div className="spread">
                  <div>
                    <h3 style={{ margin: 0 }}>{label}</h3>
                    <div className="muted small">
                      {money(o.price.amount)} / {o.price.months === 1 ? 'month' : `${o.price.months} months`}
                    </div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="price" style={{ fontSize: '1.6rem', marginTop: 0 }}>{money(o.amountDue)}</div>
                    <div className="muted small">to pay now</div>
                  </div>
                </div>
                <p className="small muted">
                  {money(o.price.amount)} − {money(o.credit)} already paid for your {PLAN_LABELS[o.from]} plan
                </p>
                <ul className="price-features">
                  {supportHighlights(o.price.support).map((line) => <li key={line}>{line}</li>)}
                  <li>HandoffCheck</li>
                </ul>
                {chosen === o.price.plan ? (
                  <form onSubmit={onPay}>
                    {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
                    <DemoCardFields card={card} onChange={setCard} />
                    <p className="small muted">
                      Your {label} plan replaces your {PLAN_LABELS[o.from]} plan and starts today. It is applied to your
                      account <strong>{user?.accountCode}</strong> as soon as you pay.
                    </p>
                    <div className="row">
                      <button className="btn btn-primary" disabled={busy}>
                        {busy ? 'Processing…' : `Pay ${money(o.amountDue)} and upgrade`}
                      </button>
                      <button type="button" className="btn btn-ghost" disabled={busy}
                              onClick={() => { setChosen(null); setError(null); }}>
                        Cancel
                      </button>
                    </div>
                  </form>
                ) : (
                  <button type="button" className="btn btn-primary" disabled={busy} onClick={() => choose(o.price.plan)}>
                    Choose {label}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
