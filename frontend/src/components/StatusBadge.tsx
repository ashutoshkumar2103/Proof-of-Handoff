import { Fragment } from 'react';
import type { HandoffStatus, TicketStatus } from '../api/types';
import type { BadgeTone } from '../lib/format';
import { LIFECYCLE_ORDER, STATUS_LABELS, STATUS_TONE, TICKET_STATUS_LABELS, TICKET_STATUS_TONE } from '../lib/format';

function ToneBadge({ tone, label }: { tone: BadgeTone; label: string }) {
  const cls = tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
  return <span className={`${cls} badge-dot`}>{label}</span>;
}

export function StatusBadge({ status }: { status: HandoffStatus }) {
  return <ToneBadge tone={STATUS_TONE[status]} label={STATUS_LABELS[status]} />;
}

/**
 * Where a handoff is on its usual path, from Draft to Closed: the steps already passed, the current one highlighted, the rest dimmed.
 * Uses the same statuses and labels as the badge. A state off the path (rejected, cancelled, disputed …) leaves the path dimmed and
 * is shown after it as its own badge.
 */
export function LifecycleSteps({ status }: { status: HandoffStatus }) {
  const at = LIFECYCLE_ORDER.indexOf(status);
  return (
    <div className="lifecycle" role="list" aria-label="Handoff progress">
      {LIFECYCLE_ORDER.map((step, i) => (
        <Fragment key={step}>
          {i > 0 && <span className="lifecycle-arrow" aria-hidden="true">→</span>}
          <span role="listitem" aria-current={i === at ? 'step' : undefined}
                className={`lifecycle-chip${i === at ? ' current' : i < at ? ' done' : ''}`}>
            {STATUS_LABELS[step]}
          </span>
        </Fragment>
      ))}
      {at < 0 && (
        <>
          <span className="lifecycle-arrow" aria-hidden="true">→</span>
          <span role="listitem" aria-current="step"><StatusBadge status={status} /></span>
        </>
      )}
    </div>
  );
}

export function TicketStatusBadge({ status }: { status: TicketStatus }) {
  return <ToneBadge tone={TICKET_STATUS_TONE[status]} label={TICKET_STATUS_LABELS[status]} />;
}
