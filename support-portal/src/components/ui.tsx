import { HttpError } from '../api/client';
import type { SubscriptionPlan, SupportPriority, TicketStatus } from '../api/types';
import { PLAN_LABELS, PRIORITY_LABELS, STATUS_LABELS, STATUS_TONE } from '../lib/format';

export function Spinner() {
  return <p className="muted center mt-3">Loading…</p>;
}

/** A user-friendly message from anything thrown. */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpError) {
    if (error.error.errors?.length) return error.error.errors.map((e) => e.message).join(' ');
    return error.error.detail ?? 'Request failed.';
  }
  return error instanceof Error ? error.message : 'Request failed.';
}

export function ErrorNotice({ error }: { error: unknown }) {
  return <div className="notice notice-error">{errorMessage(error)}</div>;
}

export function StatusBadge({ status }: { status: TicketStatus }) {
  const tone = STATUS_TONE[status];
  return <span className={`badge badge-dot ${tone === 'neutral' ? '' : `badge-${tone}`}`}>{STATUS_LABELS[status]}</span>;
}

export function PlanBadge({ plan }: { plan: SubscriptionPlan }) {
  return <span className="badge">{PLAN_LABELS[plan]}</span>;
}

/** The support priority the backend derived from the customer's plan. */
export function PriorityBadge({ priority }: { priority: SupportPriority }) {
  return <span className={`badge ${priority === 'NORMAL' ? '' : 'badge-primary'}`}>{PRIORITY_LABELS[priority]}</span>;
}

export function Pager({ page, onPage }: {
  page: { page: number; totalPages: number; first: boolean; last: boolean; totalElements: number };
  onPage: (n: number) => void;
}) {
  if (page.totalPages <= 1) return null;
  return (
    <div className="spread mt-2">
      <button className="btn btn-sm" disabled={page.first} onClick={() => onPage(page.page - 1)}>Previous</button>
      <span className="small muted">Page {page.page + 1} of {page.totalPages} · {page.totalElements} in total</span>
      <button className="btn btn-sm" disabled={page.last} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>
  );
}
