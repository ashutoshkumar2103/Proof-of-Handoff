import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { supportApi } from '../api/endpoints';
import type { TicketStatus } from '../api/types';
import { ErrorNotice, Pager, PlanBadge, PriorityBadge, Spinner, StatusBadge } from '../components/ui';
import { ACTIVE_STATUSES, CATEGORY_LABELS, CONTACT_LABELS, formatDateTime, STATUS_LABELS, STATUSES } from '../lib/format';

/** The ticket list. Filters live in the address, so dashboard tiles and customer pages can link straight to a view. */
export function TicketsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const statusParam = params.get('status');            // none = needs attention, ALL = everything, else one status
  const priorityOnly = params.get('priority') === '1';
  const account = params.get('account') ?? '';
  const page = Number(params.get('page') ?? 0) || 0;

  const statuses: TicketStatus[] | undefined =
    statusParam === 'ALL' ? undefined
      : statusParam && (STATUSES as string[]).includes(statusParam) ? [statusParam as TicketStatus]
        : ACTIVE_STATUSES;

  const tickets = useQuery({
    queryKey: ['tickets', statuses, priorityOnly, account, page],
    queryFn: () => supportApi.tickets({ status: statuses, priorityOnly, accountCode: account || undefined, page }),
  });

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) next.delete(key); else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    setParams(next);
  };

  return (
    <div className="stack">
      <div>
        <h1>Tickets</h1>
        <p className="muted">Newest activity first.</p>
      </div>

      <div className="card">
        <div className="row">
          <label className="row small" style={{ gap: '0.4rem' }}>
            Show
            <select value={statusParam ?? ''} onChange={(e) => update({ status: e.target.value || null })}>
              <option value="">Needs attention</option>
              <option value="ALL">All tickets</option>
              {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABELS[s]}</option>)}
            </select>
          </label>
          <label className="row small" style={{ gap: '0.4rem' }}>
            <input type="checkbox" checked={priorityOnly}
                   onChange={(e) => update({ priority: e.target.checked ? '1' : null })} />
            Priority customers only
          </label>
          {account && (
            <span className="badge badge-primary">
              Customer {account}
              <button type="button" className="chip-x" aria-label="Clear customer filter"
                      onClick={() => update({ account: null })}>✕</button>
            </span>
          )}
        </div>

        {tickets.isLoading ? <Spinner /> : tickets.isError ? <ErrorNotice error={tickets.error} /> : (
          tickets.data!.content.length === 0 ? <p className="muted center mt-3">No tickets match.</p> : (
            <>
              <div className="table-wrap mt-2">
                <table>
                  <thead>
                    <tr>
                      <th>Ticket</th><th>Customer</th><th>Contact</th><th>Plan</th><th>Priority</th><th>Via</th><th>Subject</th>
                      <th>Status</th><th>Opened</th><th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tickets.data!.content.map((t) => (
                      <tr key={t.ticketCode} className="clickable" onClick={() => navigate(`/tickets/${t.ticketCode}`)}>
                        <td className="small">{t.ticketCode}</td>
                        <td>{t.customerName}<div className="small muted">{t.accountCode}</div></td>
                        <td className="small">{t.customerEmail}<div className="muted">{t.customerPhone ?? '—'}</div></td>
                        <td><PlanBadge plan={t.plan} /></td>
                        <td><PriorityBadge priority={t.priority} /></td>
                        <td className="small">{CONTACT_LABELS[t.contactMethod]}</td>
                        <td><strong>{t.subject}</strong><div className="small muted">{CATEGORY_LABELS[t.category]}</div></td>
                        <td><StatusBadge status={t.status} /></td>
                        <td className="small muted">{formatDateTime(t.createdAt)}</td>
                        <td className="small muted">{formatDateTime(t.updatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={tickets.data!} onPage={(n) => update({ page: String(n) })} />
            </>
          )
        )}
      </div>
    </div>
  );
}
