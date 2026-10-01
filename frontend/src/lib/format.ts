import type { HandoffStatus } from '../api/types';

export function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
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

type BadgeTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

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

export const CONDITION_LABELS: Record<string, string> = {
  GOOD: 'Good', DAMAGED: 'Damaged', MISSING: 'Missing', OTHER: 'Other', RECOVERED: 'Received (found)',
};
