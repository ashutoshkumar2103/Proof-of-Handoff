import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ticketApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { TicketStatusBadge } from '../components/StatusBadge';
import { ErrorNotice, Spinner } from '../components/ui';
import { formatDate, TICKET_CATEGORY_LABELS } from '../lib/format';

/** A phone number as a dialable `tel:` target (spaces, dashes and brackets removed). */
const telHref = (phone: string) => `tel:${phone.replace(/[^\d+]/g, '')}`;

/** Dashboard → Contact Support. Offers only what the customer's plan includes. */
export function SupportPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const notice = (useLocation().state as { notice?: string } | null)?.notice;
  const [page, setPage] = useState(0);
  const support = user!.support;   // the route is only reachable when signed in

  const tickets = useQuery({
    queryKey: ['tickets', page],
    queryFn: () => ticketApi.list(page),
    enabled: support.ticket,
  });

  return (
    <div className="stack page-narrow">
      <div className="spread">
        <div>
          <h1>Contact Support</h1>
          <p className="muted">
            How can we help? Quote your Account ID <strong>{user!.accountCode}</strong> if you contact us any other way.
          </p>
        </div>
        <div className="row">
          {support.call && support.supportPhone && (
            <a className="btn" href={telHref(support.supportPhone)}>Call Support · {support.supportPhone}</a>
          )}
          {support.ticket && <Link to="/support/new" className="btn btn-primary">Create ticket</Link>}
          {support.message && !support.ticket && <Link to="/support/message" className="btn btn-primary">Send message</Link>}
        </div>
      </div>

      {notice && <div className="notice notice-success">{notice}</div>}

      {support.message && !support.ticket && (
        <div className="card">
          <h2>Send us a message</h2>
          <p className="muted">
            Write to our support team about anything you need help with, and attach a file if it helps.
            We will reply to your email address.
          </p>
        </div>
      )}

      {support.ticket && (
        <div className="card">
          <h2>Your tickets</h2>
          {tickets.isLoading ? <Spinner /> : tickets.isError ? <ErrorNotice error={tickets.error} /> : (
            tickets.data!.content.length === 0 ? (
              <p className="muted">You have not opened any tickets yet.</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr><th>Ticket</th><th>Subject</th><th>About</th><th>Status</th><th>Last update</th></tr>
                    </thead>
                    <tbody>
                      {tickets.data!.content.map((t) => (
                        <tr key={t.ticketCode} style={{ cursor: 'pointer' }}
                            onClick={() => navigate(`/support/tickets/${t.ticketCode}`)}>
                          <td className="small muted">{t.ticketCode}</td>
                          <td><strong>{t.subject}</strong></td>
                          <td className="small">{TICKET_CATEGORY_LABELS[t.category]}</td>
                          <td><TicketStatusBadge status={t.status} /></td>
                          <td className="small muted">{formatDate(t.updatedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {tickets.data!.totalPages > 1 && (
                  <div className="spread mt-2">
                    <button className="btn btn-sm" disabled={tickets.data!.first} onClick={() => setPage(page - 1)}>Newer</button>
                    <span className="small muted">Page {tickets.data!.page + 1} of {tickets.data!.totalPages}</span>
                    <button className="btn btn-sm" disabled={tickets.data!.last} onClick={() => setPage(page + 1)}>Older</button>
                  </div>
                )}
              </>
            )
          )}
        </div>
      )}
    </div>
  );
}
