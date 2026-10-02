import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { paymentApi, publicApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { AuthShell } from '../components/AuthShell';
import { DemoCardFields } from '../components/DemoCardFields';
import { errorMessage, Spinner } from '../components/ui';
import { cardProblem, EMPTY_CARD, setPendingPayment } from '../lib/checkout';
import type { CardInput } from '../lib/checkout';
import { formatMoney, PLAN_LABELS, supportHighlights } from '../lib/format';

/**
 * Where "Choose <plan>" on the pricing page leads: pay with a demo card first, then sign up or sign in, and the
 * plan is applied to that account automatically. This is a DEMO payment environment — no money moves, only the
 * listed test cards work, and a real card is refused before anything is sent. A real provider replaces the card
 * form, and everything after paying stays as it is. The price shown is the plan's list price from the backend,
 * and the backend (not this page) decides what plan the payment buys.
 */
export function CheckoutPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { user, applyPendingPayment } = useAuth();
  const [card, setCard] = useState<CardInput>(EMPTY_CARD);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const prices = useQuery({ queryKey: ['public-plans'], queryFn: publicApi.plans, retry: false });
  const price = prices.data?.find((p) => p.plan === params.get('plan'));
  // Nothing to buy only on a plan that is active with no end date; a plan that has lapsed (or runs out) can be renewed.
  const onThisPlan = !!price && user?.subscription.plan === price.plan;
  const alreadyOnPlan = onThisPlan && user!.subscription.status === 'ACTIVE' && !user!.subscription.validUntil;
  const renewing = onThisPlan && !alreadyOnPlan;

  async function onPay(e: React.FormEvent) {
    e.preventDefault();
    if (!price) return;
    setError(null);
    // Checked here first so that a real card number is never sent anywhere; the backend checks again.
    const problem = cardProblem(card);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      const receipt = await paymentApi.payDemo(price.plan,
        { cardNumber: card.number, expiry: card.expiry, cvc: card.cvc });
      setPendingPayment({ token: receipt.token, plan: receipt.plan });
      if (user) {
        // Already signed in: apply the plan to this account right away.
        navigate('/dashboard', { state: { notice: await applyPendingPayment() } });
      } else {
        navigate('/register');
      }
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      <div className="card auth-card">
        <div className="brand">Hand<span>Offly</span></div>
        <p className="muted center mb-2">Checkout</p>

        {prices.isLoading ? <Spinner /> : !price ? (
          <>
            <div className="notice notice-error mb-2">
              {prices.isError ? 'Plans are unavailable right now. Please try again shortly.' : 'That plan does not exist.'}
            </div>
            <Link to="/#pricing" className="btn btn-block">Back to pricing</Link>
          </>
        ) : (
          <form onSubmit={onPay}>
            {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
            <h2 className="center">{PLAN_LABELS[price.plan]} plan</h2>
            <div className="price center">
              {formatMoney(price.amount, price.currency)}
              <span className="price-period"> / {price.months === 1 ? 'month' : `${price.months} months`}</span>
            </div>
            <ul className="price-features mt-2">
              {supportHighlights(price.support).map((line) => <li key={line}>{line}</li>)}
            </ul>

            <DemoCardFields card={card} onChange={setCard} />

            <p className="small muted">
              {user
                ? <>The plan is applied to your account <strong>{user.accountCode}</strong> as soon as you pay.</>
                : 'After paying you create your account (or sign in), and the plan is applied automatically.'}
            </p>

            {alreadyOnPlan && <div className="notice notice-info mb-2">You are already on the {PLAN_LABELS[price.plan]} plan.</div>}
            {renewing && (
              <div className="notice notice-info mb-2">
                {user!.subscription.status === 'INACTIVE'
                  ? <>Your {PLAN_LABELS[price.plan]} plan has ended. Paying renews it from today.</>
                  : <>Your {PLAN_LABELS[price.plan]} plan is still active. Paying adds another period after it ends.</>}
              </div>
            )}
            <button className="btn btn-primary btn-block" disabled={busy || alreadyOnPlan}>
              {busy ? 'Processing…' : `Pay ${formatMoney(price.amount, price.currency)}`}
            </button>
            <p className="center small mt-3 muted"><Link to="/#pricing">← Back to pricing</Link></p>
          </form>
        )}
      </div>
    </AuthShell>
  );
}
