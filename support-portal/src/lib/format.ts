import type {
  AuditEvent, PlanPrice, StaffRole, SubscriptionPlan, SupportPriority, TicketCategory, TicketStatus, TicketSummary,
} from '../api/types';

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

/** The last day a plan is paid for: the backend stores the instant it runs out, which is the start of the next day. */
export function lastDay(validUntil: string): string {
  return formatDate(new Date(new Date(validUntil).getTime() - 1000).toISOString());
}

/** "Until 31 Dec 2026", or that there is no end date. */
export function describeValidity(validUntil?: string | null): string {
  return validUntil ? `Until ${lastDay(validUntil)}` : 'No end date';
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/** An amount in its currency, e.g. ₹1,999 (whole units, Indian digit grouping). */
export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount);
}

const period = (months: number) => (months === 1 ? 'month' : `${months} months`);

/** Compact, e.g. "₹1,999 / 12 months" — for lists and menus. */
export function shortPrice(p: PlanPrice): string {
  return `${formatMoney(p.amount, p.currency)} / ${period(p.months)}`;
}

/** Full, e.g. "₹1,999 per 12 months (≈ ₹167/month)" — what one payment for the plan is. */
export function describePrice(p: PlanPrice): string {
  const total = `${formatMoney(p.amount, p.currency)} per ${period(p.months)}`;
  return p.months === 1 ? total : `${total} (≈ ${formatMoney(Math.round(p.amount / p.months), p.currency)}/month)`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const PLAN_LABELS: Record<SubscriptionPlan, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  HALF_YEARLY: 'Half-yearly',
  YEARLY: 'Yearly',
};

export const ROLE_LABELS: Record<StaffRole, string> = {
  ADMIN: 'Admin',
  MANAGER: 'Manager',
  TICKET_AGENT: 'Ticket agent',
};

/** The roles an administrator can hand out (UX only — the backend enforces it; admins are never made here). */
export const ASSIGNABLE_ROLES: StaffRole[] = ['MANAGER', 'TICKET_AGENT'];

const planName = (plan: string) => PLAN_LABELS[plan as SubscriptionPlan] ?? plan;
const roleName = (role: string) => ROLE_LABELS[role as StaffRole] ?? role;

/** A recorded change in words. */
export function describeChange(c: AuditEvent): string {
  switch (c.type) {
    case 'PLAN_CHANGED': return `Plan ${c.previousValue ? planName(c.previousValue) : 'none'} → ${planName(c.newValue)}`;
    case 'SUBSCRIPTION_PERIOD_CHANGED': return `Plan paid until ${c.previousValue ?? 'none'} → ${c.newValue}`;
    case 'HANDOFF_PREFIX_CHANGED': return `Handoff prefix ${c.previousValue} → ${c.newValue}`;
    case 'STAFF_CREATED': return `Staff created as ${roleName(c.newValue)}`;
    case 'STAFF_DEACTIVATED': return 'Staff deactivated';
    case 'STAFF_REACTIVATED': return 'Staff reactivated';
    case 'STAFF_ROLE_CHANGED': return `Role ${roleName(c.previousValue ?? '')} → ${roleName(c.newValue)}`;
  }
}

export const PLANS = Object.keys(PLAN_LABELS) as SubscriptionPlan[];

/** How the customer contacted support. */
export const CONTACT_LABELS: Record<TicketSummary['contactMethod'], string> = { TICKET: 'Ticket', MESSAGE: 'Message' };

export const PRIORITY_LABELS: Record<SupportPriority, string> = {
  NORMAL: 'Normal',
  PRIORITY: 'Priority',
  HIGHEST: 'Highest',
};

export const STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  WAITING_FOR_CUSTOMER: 'Waiting for customer',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
};

export const STATUSES = Object.keys(STATUS_LABELS) as TicketStatus[];

/** Tickets that still need someone's attention (mirrors the backend's "active" statuses). */
export const ACTIVE_STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER'];

export const CATEGORY_LABELS: Record<TicketCategory, string> = {
  GENERAL: 'General',
  HANDOFF: 'Handoff or return',
  ACCOUNT: 'Account',
  BILLING: 'Plan or billing',
  TECHNICAL: 'Technical',
};

type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

export const STATUS_TONE: Record<TicketStatus, Tone> = {
  OPEN: 'primary',
  IN_PROGRESS: 'warning',
  WAITING_FOR_CUSTOMER: 'neutral',
  RESOLVED: 'success',
  CLOSED: 'neutral',
};
