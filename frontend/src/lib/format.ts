import type { HandoffStatus, SubscriptionPlan, SupportEntitlements, TicketCategory, TicketStatus } from '../api/types';

/** Shown when a customer without an active subscription tries to start a new handoff. */
export const SUBSCRIPTION_ENDED_MESSAGE =
  'Your subscription has ended. Please subscribe to any of our plans to continue without any interruption.';

/** Shown to an account that has never had a plan (it signed up without paying), where a paid feature is used or asked for. */
export const NO_ACTIVE_SUBSCRIPTION_MESSAGE =
  'No active plan is associated with this account. Please contact our support team to activate your account.';

/** Shown where HandoffCheck is locked because the customer's plan does not include it. */
export const HANDOFFCHECK_PLAN_MESSAGE = 'HandoffCheck is available on Half-Yearly and Yearly plans.';

export const PLAN_LABELS: Record<SubscriptionPlan, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  HALF_YEARLY: 'Half-yearly',
  YEARLY: 'Yearly',
};

/**
 * What a plan includes from support, in words, built only from the entitlements the backend reports —
 * so the pricing page can never drift from what a plan really gets.
 */
export function supportHighlights(s: SupportEntitlements): string[] {
  const lines: string[] = [];
  if (!s.contactSupport) {
    lines.push('Email support via the contact page');
  } else {
    lines.push(s.ticket ? 'Contact Support & support tickets in the app' : 'Contact Support in the app (send a message)');
  }
  if (s.call) lines.push('Direct phone support');
  if (s.priority === 'PRIORITY') lines.push('Priority support');
  if (s.priority === 'HIGHEST') lines.push('Highest-priority support');
  return lines;
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

/** The last day a plan is paid for: the backend stores the instant it runs out, which is the start of the next day. */
export function lastDay(validUntil: string): string {
  return formatDate(new Date(new Date(validUntil).getTime() - 1000).toISOString());
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/** Trims trailing zeros so 50.000 shows as 50 and 2.500 as 2.5. */
export function qty(value?: string | null): string {
  if (value == null || value === '') return '0';
  const n = Number(value);
  if (Number.isNaN(n)) return value;
  return String(n);
}

/** Splits an ISO instant into local date (yyyy-mm-dd) and time (HH:mm) input values. */
export function isoToLocalParts(iso?: string | null): { date: string; time: string } {
  if (!iso) return { date: '', time: '' };
  const d = new Date(iso);
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  const s = local.toISOString();
  return { date: s.slice(0, 10), time: s.slice(11, 16) };
}

/** Combines a local date + time back into an ISO instant. Empty date → null (no due date). */
export function localPartsToIso(date: string, time: string): string | null {
  if (!date) return null;
  const t = time && time.length >= 4 ? time : '00:00';
  const d = new Date(`${date}T${t}`);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** A calendar day (yyyy-mm-dd, no time) shown in the reader's own format. Built from its parts so no time zone can move it a day. */
export function formatDay(ymd: string): string {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/** A date as the yyyy-mm-dd the report API takes, in the browser's own calendar. */
export function localDateString(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export type ReportPreset = 'TODAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_QUARTER' | 'CUSTOM';

export const REPORT_PRESET_LABELS: Record<ReportPreset, string> = {
  TODAY: 'Today',
  THIS_WEEK: 'This week',
  THIS_MONTH: 'This month',
  LAST_MONTH: 'Last month',
  THIS_QUARTER: 'This quarter',
  CUSTOM: 'Custom range',
};

/** The first and last day of a ready-made period, in the browser's calendar (a week runs Monday to Sunday). Custom has no days of its own. */
export function presetRange(preset: Exclude<ReportPreset, 'CUSTOM'>, now: Date = new Date()): { from: string; to: string } {
  const y = now.getFullYear();
  const m = now.getMonth();
  let first: Date;
  let last: Date;
  switch (preset) {
    case 'TODAY':
      first = last = new Date(y, m, now.getDate());
      break;
    case 'THIS_WEEK': {
      first = new Date(y, m, now.getDate() - ((now.getDay() + 6) % 7));
      last = new Date(first.getFullYear(), first.getMonth(), first.getDate() + 6);
      break;
    }
    case 'THIS_MONTH':
      first = new Date(y, m, 1);
      last = new Date(y, m + 1, 0);
      break;
    case 'LAST_MONTH':
      first = new Date(y, m - 1, 1);
      last = new Date(y, m, 0);
      break;
    case 'THIS_QUARTER': {
      const start = Math.floor(m / 3) * 3;
      first = new Date(y, start, 1);
      last = new Date(y, start + 3, 0);
      break;
    }
  }
  return { from: localDateString(first), to: localDateString(last) };
}

/** An amount in its currency, e.g. ₹1,999 (whole units, Indian digit grouping). */
export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const STATUS_LABELS: Record<HandoffStatus, string> = {
  DRAFT: 'Draft',
  OUTGOING_SENT: 'Outgoing sent',
  AWAITING_RECIPIENT: 'Awaiting recipient',
  ACTIVE_WITH_RECIPIENT: 'Active with recipient',
  RETURN_PENDING: 'Return pending',
  PARTIALLY_RETURNED: 'Partially returned',
  FULLY_RETURNED: 'Fully returned',
  CLOSED: 'Closed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  DISPUTED: 'Disputed',
  OVERDUE: 'Overdue',
};

export type BadgeTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

export const STATUS_TONE: Record<HandoffStatus, BadgeTone> = {
  DRAFT: 'neutral',
  OUTGOING_SENT: 'primary',
  AWAITING_RECIPIENT: 'primary',
  ACTIVE_WITH_RECIPIENT: 'primary',
  RETURN_PENDING: 'warning',
  PARTIALLY_RETURNED: 'warning',
  FULLY_RETURNED: 'success',
  CLOSED: 'danger',
  REJECTED: 'danger',
  CANCELLED: 'danger',
  DISPUTED: 'danger',
  OVERDUE: 'danger',
};

/**
 * The resting states of a handoff in the order it normally moves through them, for showing where it is. Display only: which
 * moves are allowed is the backend's (HandoffStateMachine). A state off this path (rejected, cancelled, disputed …) is shown apart.
 */
export const LIFECYCLE_ORDER: HandoffStatus[] = [
  'DRAFT', 'AWAITING_RECIPIENT', 'ACTIVE_WITH_RECIPIENT', 'RETURN_PENDING', 'PARTIALLY_RETURNED', 'FULLY_RETURNED', 'CLOSED',
];

/**
 * Statuses with nothing left to do: the handoff is read-only and its story lives in the event history.
 * Presentation only — mirrors the backend's terminal states; the backend stays the authority.
 */
const FINISHED_STATUSES: HandoffStatus[] = ['CLOSED', 'REJECTED', 'CANCELLED'];
export const isFinished = (status: HandoffStatus) => FINISHED_STATUSES.includes(status);

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  WAITING_FOR_CUSTOMER: 'Waiting for you',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
};

export const TICKET_STATUS_TONE: Record<TicketStatus, BadgeTone> = {
  OPEN: 'primary',
  IN_PROGRESS: 'warning',
  WAITING_FOR_CUSTOMER: 'danger',
  RESOLVED: 'success',
  CLOSED: 'neutral',
};

export const TICKET_CATEGORY_LABELS: Record<TicketCategory, string> = {
  GENERAL: 'General question',
  HANDOFF: 'A handoff or return',
  ACCOUNT: 'My account',
  BILLING: 'Plan or billing',
  TECHNICAL: 'Something is not working',
};

export const CONDITION_LABELS: Record<string, string> = {
  GOOD: 'Good', DAMAGED: 'Damaged', MISSING: 'Missing', OTHER: 'Other', RECOVERED: 'Received (found)',
};
