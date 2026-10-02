import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { saveBlob } from '../api/client';
import { supportApi } from '../api/endpoints';
import type { TicketAttachment, TicketDetail, TicketStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, ErrorNotice, PlanBadge, PriorityBadge, Spinner, StatusBadge } from '../components/ui';
import { CATEGORY_LABELS, CONTACT_LABELS, formatBytes, formatDateTime, STATUS_LABELS, STATUSES } from '../lib/format';

/** How often an open ticket is re-checked for new messages. */
const TICKET_POLL_MS = 4000;

/** One ticket: the customer's request and the conversation, with a status control and a reply box. */
export function TicketPage() {
  const { ticketId: code = '' } = useParams();
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = ['ticket', code];
  // New customer replies appear on their own: re-check while the tab is visible, until the ticket is closed (final).
  const ticket = useQuery({
    queryKey,
    queryFn: () => supportApi.ticket(code),
    refetchInterval: (query) => (query.state.data?.ticket.status === 'CLOSED' ? false : TICKET_POLL_MS),
  });

  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<TicketDetail>, onDone?: () => void) {
    setError(null);
    setBusy(true);
    try {
      queryClient.setQueryData(queryKey, await action());
      await queryClient.invalidateQueries({ queryKey: ['tickets'] });
      await queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      onDone?.();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  function changeStatus(status: TicketStatus) {
    if (status === 'CLOSED'
      && !window.confirm('Close this ticket? A closed ticket is final: nobody can reply to it or reopen it.')) {
      return;
    }
    void run(() => supportApi.setStatus(code, status));
  }

  async function download(a: TicketAttachment) {
    try {
      saveBlob(await supportApi.downloadAttachment(code, a.id), a.originalFilename);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  if (ticket.isLoading) return <Spinner />;
  if (ticket.isError && !ticket.data) return <ErrorNotice error={ticket.error} />;   // a failed re-check keeps what is shown
  const { ticket: t, description, messages, attachments } = ticket.data!;
  const closed = t.status === 'CLOSED';

  return (
    <div className="stack">
      <div>
        <Link to="/tickets" className="small">← Tickets</Link>
        <div className="spread">
          <h1>{t.subject}</h1>
          <StatusBadge status={t.status} />
        </div>
        <p className="muted small">{t.ticketCode} · {CATEGORY_LABELS[t.category]} · opened {formatDateTime(t.createdAt)}</p>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}

      <div className="two-col">
        <div className="stack">
          <div className="card">
            <div className="thread">
              <div className="msg">
                <div className="msg-meta">{t.customerName} · {formatDateTime(t.createdAt)} · original request</div>
                {description}
              </div>
              {messages.map((m) => (
                <div key={m.id} className={`msg ${m.author === 'SUPPORT' ? 'mine' : ''}`}>
                  <div className="msg-meta">
                    {m.author === 'SUPPORT' ? `${m.authorName} (support)` : m.authorName} · {formatDateTime(m.createdAt)}
                  </div>
                  {m.body}
                </div>
              ))}
            </div>
          </div>

          {closed ? (
            <div className="notice notice-info">This ticket is closed. It can no longer be replied to or changed.</div>
          ) : (
            <form className="card" onSubmit={(e) => { e.preventDefault(); void run(() => supportApi.reply(code, reply), () => setReply('')); }}>
              <div className="field">
                <label htmlFor="reply">Reply to {t.customerName}</label>
                <textarea id="reply" rows={5} maxLength={5000} value={reply}
                          onChange={(e) => setReply(e.target.value)} required />
                <p className="small muted">The customer is emailed this reply. Replying does not change the status.</p>
              </div>
              <button className="btn btn-primary" disabled={busy || !reply.trim()}>{busy ? 'Sending…' : 'Send reply'}</button>
            </form>
          )}
        </div>

        <div className="stack">
          <div className="card">
            <h2>Status</h2>
            <select aria-label="Ticket status" value={t.status} disabled={busy || closed}
                    onChange={(e) => changeStatus(e.target.value as TicketStatus)}>
              {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABELS[s]}</option>)}
            </select>
          </div>

          <div className="card">
            <h2>Customer</h2>
            <dl className="kv">
              <dt>Account ID</dt><dd>{can('VIEW_CUSTOMERS') ? <Link to={`/customers/${t.accountCode}`}>{t.accountCode}</Link> : t.accountCode}</dd>
              <dt>Name</dt><dd>{t.customerName}</dd>
              <dt>Email</dt><dd><a href={`mailto:${t.customerEmail}`}>{t.customerEmail}</a></dd>
              <dt>Phone</dt>
              <dd>{t.customerPhone ? <a href={`tel:${t.customerPhone.replace(/[^\d+]/g, '')}`}>{t.customerPhone}</a> : '—'}</dd>
              <dt>Plan</dt><dd><PlanBadge plan={t.plan} /></dd>
              <dt>Priority</dt><dd><PriorityBadge priority={t.priority} /></dd>
              <dt>Contact method</dt><dd>{CONTACT_LABELS[t.contactMethod]}</dd>
              <dt>Handoff</dt><dd>{t.handoffReference ?? '—'}</dd>
              <dt>Last update</dt><dd>{formatDateTime(t.updatedAt)}</dd>
            </dl>
          </div>

          <div className="card">
            <h2>Attachments</h2>
            {attachments.length === 0 ? <p className="muted small">None.</p> : (
              <div className="stack">
                {attachments.map((a) => (
                  <div key={a.id} className="spread">
                    <button className="btn btn-sm btn-ghost" onClick={() => void download(a)}>{a.originalFilename}</button>
                    <span className="small muted">{formatBytes(a.sizeBytes)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
