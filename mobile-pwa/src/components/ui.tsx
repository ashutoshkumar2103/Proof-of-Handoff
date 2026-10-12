import type { ReactNode } from 'react';
import { HttpError } from '../api/client';
import type { HandoffStatus } from '../api/types';
import { STATUS_LABELS, STATUS_TONE } from '../lib/format';

/** The text to show for anything that went wrong, never a stack trace. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpError) {
    const fields = err.error.errors?.map((e) => e.message).filter(Boolean);
    return fields && fields.length > 0 ? `${err.message} ${fields.join(' ')}` : err.message;
  }
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.';
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="spinner-wrap" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span className="muted">{label ?? 'Loading…'}</span>
    </div>
  );
}

/** A failed load: stays for as long as it is true, with a way to try again. */
export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="notice notice-error" role="alert">
      <div>{errorMessage(error)}</div>
      {onRetry && <button type="button" className="btn btn-sm mt-1" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <h2>{title}</h2>
      {children && <p className="muted">{children}</p>}
      {action}
    </div>
  );
}

export function StatusBadge({ status }: { status: HandoffStatus }) {
  const tone = STATUS_TONE[status];
  return <span className={`badge ${tone === 'neutral' ? '' : `badge-${tone}`} badge-dot`}>{STATUS_LABELS[status]}</span>;
}

/** A result message that clears itself (see `useTransient`). Announced politely to screen readers. */
export function Toast({ tone, children }: { tone: 'success' | 'info' | 'error'; children: ReactNode }) {
  return (
    <div className="toast-area" role="status" aria-live="polite">
      <div className={`notice notice-${tone} toast`}>{children}</div>
    </div>
  );
}
