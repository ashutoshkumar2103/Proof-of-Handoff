import { getPendingPayment } from '../lib/checkout';
import { PLAN_LABELS } from '../lib/format';

/** Shown on the sign-up / sign-in pages when a plan was paid for first: finishing here activates it. */
export function PendingPaymentNotice({ action }: { action: string }) {
  const pending = getPendingPayment();
  if (!pending) return null;
  return (
    <div className="notice notice-success mb-2">
      Payment received for the <strong>{PLAN_LABELS[pending.plan]}</strong> plan. {action} to activate it.
    </div>
  );
}
