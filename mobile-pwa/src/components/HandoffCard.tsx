import { Link } from 'react-router-dom';
import type { HandoffSummary } from '../api/types';
import { formatDate, qty, REFERENCE_PENDING } from '../lib/format';
import { StatusBadge } from './ui';

/**
 * One handoff in a list: reference and status on top, the title, who it is for, and one line of quantities. A draft has given nothing
 * yet, so it shows its item count instead of figures that would all read zero. Everything is the backend's own summary; the card
 * only lays it out, and opens the handoff.
 */
export function HandoffCard({ h }: { h: HandoffSummary }) {
  const sent = !!h.outgoingAt;
  return (
    <Link to={`/h/${h.id}`} className="card card-link handoff-card">
      <div className="spread spread-nowrap">
        <span className="handoff-ref">{h.publicCode ?? REFERENCE_PENDING}</span>
        <span className="row row-tight row-nowrap">
          {h.overdue && <span className="badge badge-danger">Overdue</span>}
          <StatusBadge status={h.status} />
        </span>
      </div>
      <h2 className="handoff-title">{h.title}</h2>
      <p className="muted small handoff-sub">To {h.recipientName}{h.dueAt ? ` · due ${formatDate(h.dueAt)}` : ''}</p>
      {sent ? (
        <dl className="figures-inline">
          <div><dt>Given</dt><dd>{qty(h.totalOutgoing)}</dd></div>
          <div><dt>Returned</dt><dd>{qty(h.totalReturned)}</dd></div>
          <div><dt>Missing</dt><dd className={Number(h.totalMissing) > 0 ? 'neg' : ''}>{qty(h.totalMissing)}</dd></div>
          <div><dt>Remaining</dt><dd className={Number(h.totalRemaining) > 0 ? 'open' : 'zero'}>{qty(h.totalRemaining)}</dd></div>
        </dl>
      ) : (
        <p className="muted small handoff-sub">{h.itemCount} {h.itemCount === 1 ? 'item' : 'items'} · not sent yet</p>
      )}
    </Link>
  );
}
