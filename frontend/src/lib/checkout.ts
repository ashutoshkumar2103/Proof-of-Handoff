import type { SubscriptionPlan } from '../api/types';

/**
 * A payment made before the customer has signed up or signed in. The token carries it across the sign-up /
 * sign-in step so the plan can be applied right after (it is not a plan choice — the backend checks the payment).
 * Kept in this browser only; it expires on the backend if it is never used.
 */
export interface PendingPayment {
  token: string;
  plan: SubscriptionPlan;
}

/**
 * The demo provider's test cards (public test numbers that work nowhere in the real world). Shown on the checkout
 * page, and the only numbers it will send: a real card number never leaves the browser. The backend knows the same
 * list and is the one that decides — this only keeps the page honest.
 */
export const DEMO_CARDS = [
  { number: '4242 4242 4242 4242', outcome: 'payment succeeds' },
  { number: '4000 0000 0000 0002', outcome: 'card is declined' },
] as const;

export const isDemoCard = (typed: string) =>
  DEMO_CARDS.some((card) => card.number.replace(/\s/g, '') === typed.replace(/[\s-]/g, ''));

/** What has been typed into the demo card form. */
export interface CardInput {
  number: string;
  expiry: string;
  cvc: string;
}

export const EMPTY_CARD: CardInput = { number: '', expiry: '', cvc: '' };

const EXPIRY = /^(0[1-9]|1[0-2])\/(\d{2})$/;

/** Groups typed digits as 4242 4242 4242 4242. */
export const formatCardNumber = (typed: string) => typed.replace(/\D/g, '').slice(0, 19).replace(/(\d{4})(?=\d)/g, '$1 ');

/** Keeps digits only and adds the slash: 1230 -> 12/30. */
export const formatExpiry = (typed: string) => {
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
 * What is wrong with the card as typed, if anything. Checked before anything is sent, so that a real card number is never sent
 * anywhere; the backend checks again.
 */
export function cardProblem(card: CardInput): string | null {
  return !isDemoCard(card.number)
    ? 'This is a demo payment page: use one of the test cards below. Real cards are not accepted.'
    : expiryProblem(card.expiry) ?? (/^\d{3,4}$/.test(card.cvc) ? null : 'Enter the 3 or 4 digit security code.');
}

const KEY = 'handoffly.pendingPayment';

export function getPendingPayment(): PendingPayment | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as PendingPayment) : null;
  } catch {
    return null;
  }
}

export function setPendingPayment(payment: PendingPayment) {
  try {
    localStorage.setItem(KEY, JSON.stringify(payment));
  } catch {
    // Storage unavailable: the customer would have to pay again; nothing else to do here.
  }
}

export function clearPendingPayment() {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
