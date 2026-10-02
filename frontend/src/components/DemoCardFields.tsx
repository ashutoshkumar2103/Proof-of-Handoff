import { DEMO_CARDS, formatCardNumber, formatExpiry } from '../lib/checkout';
import type { CardInput } from '../lib/checkout';

/**
 * The demo payment form: the notice that no money moves, the card fields and the test cards that work. Every place that takes a
 * (demo) payment uses it, so they look and behave the same. What was typed is checked by `cardProblem` (lib/checkout) before
 * anything is sent, and by the backend again.
 */
export function DemoCardFields({ card, onChange }: { card: CardInput; onChange: (card: CardInput) => void }) {
  return (
    <>
      <div className="notice notice-warning mb-2">
        <strong>Demo payment environment.</strong> No real money is charged. Pay with a test card below — real
        cards are not accepted.
      </div>

      <div className="field">
        <label htmlFor="cardNumber">Card number</label>
        <input id="cardNumber" inputMode="numeric" autoComplete="off" placeholder="4242 4242 4242 4242"
               value={card.number} onChange={(e) => onChange({ ...card, number: formatCardNumber(e.target.value) })} required />
      </div>
      <div className="field-row">
        <div className="field">
          <label htmlFor="expiry">Expiry</label>
          <input id="expiry" inputMode="numeric" autoComplete="off" placeholder="MM/YY" maxLength={5}
                 value={card.expiry} onChange={(e) => onChange({ ...card, expiry: formatExpiry(e.target.value) })} required />
        </div>
        <div className="field">
          <label htmlFor="cvc">Security code</label>
          <input id="cvc" inputMode="numeric" autoComplete="off" placeholder="123" maxLength={4}
                 value={card.cvc} onChange={(e) => onChange({ ...card, cvc: e.target.value.replace(/\D/g, '') })} required />
        </div>
      </div>

      <div className="notice notice-info small mb-2">
        <strong>Test cards</strong> (any future expiry, any 3 digit code):
        {DEMO_CARDS.map((c) => <div key={c.number}><code>{c.number}</code> — {c.outcome}</div>)}
      </div>
    </>
  );
}
