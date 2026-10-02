import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { supportApi } from '../api/endpoints';
import { ErrorNotice, PlanBadge, PriorityBadge, Spinner, StatusBadge } from '../components/ui';
import { ACTIVE_STATUSES, CATEGORY_LABELS, CONTACT_LABELS, formatDateTime } from '../lib/format';

/** The support desk at a glance: counts by status, priority customers, and the tickets that need attention. */
export function DashboardPage() {
  const navigate = useNavigate();
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: supportApi.dashboard });
  const recent = useQuery({
    queryKey: ['tickets', 'attention', 10],
    queryFn: () => supportApi.tickets({ status: ACTIVE_STATUSES, size: 10 }),
  });

  return (
    <div className="stack">
      <div>
        <h1>Dashboard</h1>
        <p className="muted">Tickets that need attention, newest activity first.</p>
      </div>

      {dashboard.isLoading ? <Spinner /> : dashboard.isError ? <ErrorNotice error={dashboard.error} /> : (
        <div className="stat-grid">
          <Tile label="Open" n={dashboard.data!.open} to="/tickets?status=OPEN" />
          <Tile label="In progress" n={dashboard.data!.inProgress} to="/tickets?status=IN_PROGRESS" />
          <Tile label="Waiting for customer" n={dashboard.data!.waitingForCustomer} to="/tickets?status=WAITING_FOR_CUSTOMER" />
          {dashboard.data!.priorityCustomers != null && (
            <Tile label="Priority customers" n={dashboard.data!.priorityCustomers} to="/tickets?priority=1" highlight />
          )}
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2>Tickets needing attention</h2>
          <Link to="/tickets" className="small">View all tickets</Link>
        </div>
        {recent.isLoading ? <Spinner /> : recent.isError ? <ErrorNotice error={recent.error} /> : (
          recent.data!.content.length === 0 ? <p className="muted">Nothing needs attention. 🎉</p> : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>Ticket</th><th>Customer</th><th>Contact</th><th>Plan</th><th>Priority</th><th>Via</th><th>Subject</th><th>Status</th><th>Updated</th></tr>
                </thead>
                <tbody>
                  {recent.data!.content.map((t) => (
                    <tr key={t.ticketCode} className="clickable" onClick={() => navigate(`/tickets/${t.ticketCode}`)}>
                      <td className="small">{t.ticketCode}</td>
                      <td>{t.customerName}<div className="small muted">{t.accountCode}</div></td>
                      <td className="small">{t.customerEmail}<div className="muted">{t.customerPhone ?? '—'}</div></td>
                      <td><PlanBadge plan={t.plan} /></td>
                      <td><PriorityBadge priority={t.priority} /></td>
                      <td className="small">{CONTACT_LABELS[t.contactMethod]}</td>
                      <td><strong>{t.subject}</strong><div className="small muted">{CATEGORY_LABELS[t.category]}</div></td>
                      <td><StatusBadge status={t.status} /></td>
                      <td className="small muted">{formatDateTime(t.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>
    </div>
  );
}

function Tile({ label, n, to, highlight }: { label: string; n: number; to: string; highlight?: boolean }) {
  return (
    <Link to={to} className={`stat ${highlight ? 'stat-priority' : ''}`}>
      <div className="n">{n}</div>
      <div className="l">{label}</div>
    </Link>
  );
}
