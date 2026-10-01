import type { HandoffStatus } from '../api/types';
import { STATUS_LABELS, STATUS_TONE } from '../lib/format';

export function StatusBadge({ status }: { status: HandoffStatus }) {
  const tone = STATUS_TONE[status];
  const cls = tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
  return <span className={`${cls} badge-dot`}>{STATUS_LABELS[status]}</span>;
}
