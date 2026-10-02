import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { paymentApi, publicApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { AuthShell } from '../components/AuthShell';
import { errorMessage, Spinner } from '../components/ui';
import { DEMO_CARDS, isDemoCard, setPendingPayment } from '../lib/checkout';
import { formatMoney, PLAN_LABELS, supportHighlights } from '../lib/format';

const EXPIRY = /^(0[1-9]|1[0-2])\/(\d{2})$/;

/** Groups typed digits as 4242 4242 4242 4242. */
const formatCardNumber = (typed: string) => typed.replace(/\D/g, '').slice(0, 19).replace(/(\d{4})(?=\d)/g, '$1 ');

/** Keeps digits only and adds the slash: 1230 -> 12/30. */
const formatExpiry = (typed: string) => {
  const digits = typed.replace(/\D/g, '').slice(0, 4);
  return digits.length > 2 ? `${digits.slice(0, 2)}/${digits.slice(2)}` : digits;
};

/** What is wrong with the expiry as typed, if anything (the backend checks it again). */
function expiryProblem(expiry: string): string | null {
  const m = EXPIRY.exec(expiry);
  if (!m) return 'Enter the expiry date as MM/YY.';
  const now = new Date();
  const thisMonth = now.getUTCFullYear() * 12 + now.getUTCMonth();
  const expiresMonth = (2000 + Number(m[2])) * 12 + Number(m[1]) - 1;   // good through the end of that month
  return expiresMonth < thisMonth ? 'That card has expired.' : null;
}

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
  const [card, setCard] = useState({ number: '', expiry: '', cvc: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const prices = useQuery({ queryKey: ['public-plans'], queryFn: publicApi.plans, retry: false });
  const price = prices.data?.find((p) => p.plan === params.get('plan'));
  const alreadyOnPlan = !!price && user?.plan === price.plan;

  async function onPay(e: React.FormEvent) {
    e.preventDefault();
    if (!price) return;
    setError(null);
    // Checked here first so that a real card number is never sent anywhere; the backend checks again.
    const problem = !isDemoCard(card.number)
      ? 'This is a demo payment page: use one of the test cards below. Real cards are not accepted.'
      : expiryProblem(card.expiry) ?? (/^\d{3,4}$/.test(card.cvc) ? null : 'Enter the 3 or 4 digit security code.');
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

            <div className="notice notice-warning mb-2">
              <strong>Demo payment environment.</strong> No real money is charged. Pay with a test card below — real
              cards are not accepted.
            </div>

            <div className="field">
              <label htmlFor="cardNumber">Card number</label>
              <input id="cardNumber" inputMode="numeric" autoComplete="off" placeholder="4242 4242 4242 4242"
                     value={card.number} onChange={(e) => setCard({ ...card, number: formatCardNumber(e.target.value) })} required />
            </div>
            <div className="field-row">
              <div className="field">
                <label htmlFor="expiry">Expiry</label>
                <input id="expiry" inputMode="numeric" autoComplete="off" placeholder="MM/YY" maxLength={5}
                       value={card.expiry} onChange={(e) => setCard({ ...card, expiry: formatExpiry(e.target.value) })} required />
              </div>
              <div className="field">
                <label htmlFor="cvc">Security code</label>
                <input id="cvc" inputMode="numeric" autoComplete="off" placeholder="123" maxLength={4}
                       value={card.cvc} onChange={(e) => setCard({ ...card, cvc: e.target.value.replace(/\D/g, '') })} required />
              </div>
            </div>

            <div className="notice notice-info small mb-2">
              <strong>Test cards</strong> (any future expiry, any 3 digit code):
              {DEMO_CARDS.map((c) => <div key={c.number}><code>{c.number}</code> — {c.outcome}</div>)}
            </div>

            <p className="small muted">
              {user
                ? <>The plan is applied to your account <strong>{user.accountCode}</strong> as soon as you pay.</>
                : 'After paying you create your account (or sign in), and the plan is applied automatically.'}
            </p>

            {alreadyOnPlan && <div className="notice notice-info mb-2">You are already on the {PLAN_LABELS[price.plan]} plan.</div>}
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
