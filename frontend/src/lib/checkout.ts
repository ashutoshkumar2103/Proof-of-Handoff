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
