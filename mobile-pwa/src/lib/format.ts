import type { HandoffStatus, ItemCondition, SubscriptionPlan } from '../api/types';

/** What shows where a handoff's reference would be, for a draft that has not been submitted yet (it has none until then). */
export const REFERENCE_PENDING = 'Reference assigned on submission';

export const PLAN_LABELS: Record<SubscriptionPlan, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  HALF_YEARLY: 'Half-yearly',
  YEARLY: 'Yearly',
};

/** Shown to an account that has never had a plan (it signed up without paying). */
export const NO_ACTIVE_SUBSCRIPTION_MESSAGE =
  'No active plan is associated with this account. Please contact our support team to activate your account.';

/** Shown where starting or sending a handoff needs a plan that has ended. */
export const SUBSCRIPTION_ENDED_MESSAGE =
  'Your subscription has ended. Please subscribe to any of our plans to continue without any interruption.';

export function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** The last day a plan is paid for: the backend stores the instant it runs out, which is the start of the next day. */
export function lastDay(validUntil: string): string {
  return formatDate(new Date(new Date(validUntil).getTime() - 1000).toISOString());
}

/** Trims trailing zeros so 50.000 shows as 50 and 2.500 as 2.5. */
export function qty(value?: string | number | null): string {
  if (value == null || value === '') return '0';
  const n = Number(value);
  if (Number.isNaN(n)) return String(value);
  return String(n);
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Splits an ISO instant into local date (yyyy-mm-dd) and time (HH:mm) input values. */
export function isoToLocalParts(iso?: string | null): { date: string; time: string } {
  if (!iso) return { date: '', time: '' };
  const d = new Date(iso);
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString();
  return { date: local.slice(0, 10), time: local.slice(11, 16) };
}

/** Combines a local date + time back into an ISO instant. Empty date means no due date. */
export function localPartsToIso(date: string, time: string): string | null {
  if (!date) return null;
  const d = new Date(`${date}T${time && time.length >= 4 ? time : '00:00'}`);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
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

export type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

export const STATUS_TONE: Record<HandoffStatus, Tone> = {
  DRAFT: 'neutral',
  OUTGOING_SENT: 'primary',
  AWAITING_RECIPIENT: 'primary',
  ACTIVE_WITH_RECIPIENT: 'primary',
  RETURN_PENDING: 'warning',
  PARTIALLY_RETURNED: 'warning',
  FULLY_RETURNED: 'success',
  CLOSED: 'neutral',
  REJECTED: 'danger',
  CANCELLED: 'danger',
  DISPUTED: 'danger',
  OVERDUE: 'danger',
};

/**
 * Statuses with nothing left to do. Presentation only: the backend's own state machine and `availableActions` decide what can
 * be done, this only words the page.
 */
const FINISHED_STATUSES: HandoffStatus[] = ['CLOSED', 'REJECTED', 'CANCELLED'];
export const isFinished = (status: HandoffStatus) => FINISHED_STATUSES.includes(status);

/**
 * The states a handoff can be found in, in lifecycle order, for filtering a list. (OUTGOING_SENT and RETURN_PENDING exist in the backend's enum
 * but no handoff rests in them today, so they are not offered; "Overdue" is a handoff past its return date, not a state of its own.)
 */
export const FILTER_STATUSES: HandoffStatus[] = [
  'DRAFT', 'AWAITING_RECIPIENT', 'ACTIVE_WITH_RECIPIENT', 'PARTIALLY_RETURNED', 'FULLY_RETURNED', 'CLOSED', 'REJECTED', 'CANCELLED', 'DISPUTED',
];

/** Shown where the AI Report Assistant is locked because the customer's plan does not include it. */
export const AI_REPORT_PLAN_MESSAGE = 'The AI Report Assistant is available on Half-Yearly and Yearly plans.';

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

/** The days of a report are the customer's own calendar days: the browser's zone, which the report states back. */
export const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

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
    case 'THIS_WEEK':
      first = new Date(y, m, now.getDate() - ((now.getDay() + 6) % 7));
      last = new Date(first.getFullYear(), first.getMonth(), first.getDate() + 6);
      break;
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

export const CONDITION_LABELS: Record<ItemCondition, string> = {
  GOOD: 'Good', DAMAGED: 'Damaged', MISSING: 'Missing', OTHER: 'Other', RECOVERED: 'Received (found)',
};
