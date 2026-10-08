import { useEffect, useState } from 'react';
import { HttpError } from '../api/client';
import type { SubscriptionPlan, SubscriptionStatus, SupportPriority, TicketStatus } from '../api/types';
import { PLAN_LABELS, PRIORITY_LABELS, STATUS_LABELS, STATUS_TONE } from '../lib/format';

/** How long a message about what the user just did stays on screen. */
export const TRANSIENT_NOTICE_MS = 5000;

/**
 * State for a message about the result of an action — done, refused, or failed: it clears itself after {@link TRANSIENT_NOTICE_MS}
 * of being on screen. The countdown pauses while the browser tab is in the background, so the message is never used up unseen,
 * and setting a new value restarts it. Use this for EVERY such message in the portal; plain `useState` is for things that are
 * the page's own state (a failed load, a closed ticket), which stay for as long as they are true.
 */
export function useTransient<T>(): [T | null, (value: T | null) => void] {
  const [value, setValue] = useState<T | null>(null);
  useEffect(() => {
    if (value === null) return;
    let remaining = TRANSIENT_NOTICE_MS;
    let startedAt = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const start = () => {
      startedAt = Date.now();
      timer = setTimeout(() => setValue(null), remaining);
    };
    const pause = () => {
      if (timer === undefined) return;
      clearTimeout(timer);
      timer = undefined;
      remaining = Math.max(0, remaining - (Date.now() - startedAt));
    };
    const onVisibility = () => (document.hidden ? pause() : timer === undefined && start());
    if (!document.hidden) start();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [value]);
  return [value, setValue];
}

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

/** The plan, or that there is none yet (an account that signed up without paying). */
export function PlanBadge({ plan }: { plan: SubscriptionPlan | null }) {
  return <span className="badge">{plan ? PLAN_LABELS[plan] : 'No plan'}</span>;
}

/** Whether the subscription is paid up. A lapsed one (INACTIVE) is called Expired, and stands out. */
export function SubscriptionStatusBadge({ status }: { status: SubscriptionStatus }) {
  return <span className={`badge badge-dot ${status === 'ACTIVE' ? 'badge-success' : 'badge-danger'}`}>
    {status === 'ACTIVE' ? 'Active' : 'Expired'}
  </span>;
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
