import type { HandoffStatus, TicketStatus } from '../api/types';
import type { BadgeTone } from '../lib/format';
import { STATUS_LABELS, STATUS_TONE, TICKET_STATUS_LABELS, TICKET_STATUS_TONE } from '../lib/format';

function ToneBadge({ tone, label }: { tone: BadgeTone; label: string }) {
  const cls = tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
  return <span className={`${cls} badge-dot`}>{label}</span>;
}

export function StatusBadge({ status }: { status: HandoffStatus }) {
  return <ToneBadge tone={STATUS_TONE[status]} label={STATUS_LABELS[status]} />;
}

export function TicketStatusBadge({ status }: { status: TicketStatus }) {
  return <ToneBadge tone={TICKET_STATUS_TONE[status]} label={TICKET_STATUS_LABELS[status]} />;
}
