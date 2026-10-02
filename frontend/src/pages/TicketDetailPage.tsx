import { useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { saveBlob } from '../api/client';
import { ticketApi } from '../api/endpoints';
import type { TicketAttachment } from '../api/types';
import { TicketStatusBadge } from '../components/StatusBadge';
import { errorMessage, ErrorNotice, Spinner } from '../components/ui';
import { formatBytes, formatDateTime, TICKET_CATEGORY_LABELS } from '../lib/format';

/** How often an open ticket is re-checked for new messages. */
const TICKET_POLL_MS = 4000;

/** One ticket as a conversation: the original request, then replies from both sides. */
export function TicketDetailPage() {
  const { code = '' } = useParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Passed along when a ticket was created but its attachment could not be added.
  const [carriedNotice, setCarriedNotice] = useState<string | undefined>(
    (location.state as { notice?: string } | null)?.notice);

  const queryKey = ['ticket', code];
  // New replies from support appear on their own: re-check while the tab is visible, until the ticket is closed (final).
  const ticket = useQuery({
    queryKey,
    queryFn: () => ticketApi.get(code),
    refetchInterval: (query) => (query.state.data?.ticket.status === 'CLOSED' ? false : TICKET_POLL_MS),
  });

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey });
    await queryClient.invalidateQueries({ queryKey: ['tickets'] });
  }

  async function onReply(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await ticketApi.reply(code, reply);
      setReply('');
      await refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function onAttach(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setBusy(true);
    try {
      await ticketApi.upload(code, file);
      await refresh();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function onDownload(a: TicketAttachment) {
    try {
      saveBlob(await ticketApi.download(code, a.id), a.originalFilename);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  if (ticket.isLoading) return <Spinner />;
  if (ticket.isError && !ticket.data) return <ErrorNotice error={ticket.error} />;   // a failed re-check keeps what is shown
  const { ticket: t, description, messages, attachments } = ticket.data!;
  const closed = t.status === 'CLOSED';

  return (
    <div className="stack page-narrow">
      <div>
        <Link to="/support" className="small">← Contact Support</Link>
        <div className="spread">
          <h1>{t.subject}</h1>
          <TicketStatusBadge status={t.status} />
        </div>
        <p className="muted small">
          {t.ticketCode} · {TICKET_CATEGORY_LABELS[t.category]}
          {t.handoffReference && <> · Handoff {t.handoffReference}</>}
          {' '}· opened {formatDateTime(t.createdAt)}
        </p>
      </div>

      {carriedNotice && (
        <div className="notice notice-warning spread">
          <span>{carriedNotice}</span>
          <button className="btn btn-sm btn-ghost" onClick={() => setCarriedNotice(undefined)}>Dismiss</button>
        </div>
      )}
      {error && <div className="notice notice-error">{error}</div>}

      <div className="card">
        <div className="thread">
          <div className="msg mine">
            <div className="msg-meta">You · {formatDateTime(t.createdAt)}</div>
            {description}
          </div>
          {messages.map((m) => (
            <div key={m.id} className={`msg ${m.author === 'CUSTOMER' ? 'mine' : ''}`}>
              <div className="msg-meta">
                {m.author === 'CUSTOMER' ? 'You' : `${m.authorName} · Support`} · {formatDateTime(m.createdAt)}
              </div>
              {m.body}
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Attachments</h2>
          {!closed && (
            <>
              <button className="btn btn-sm" disabled={busy} onClick={() => fileRef.current?.click()}>Attach a file</button>
              <input ref={fileRef} type="file" className="hidden" onChange={onAttach} />
            </>
          )}
        </div>
        {attachments.length === 0 ? <p className="muted">No files attached.</p> : (
          <div className="stack">
            {attachments.map((a) => (
              <div key={a.id} className="spread">
                <button className="btn btn-sm btn-ghost" onClick={() => onDownload(a)}>{a.originalFilename}</button>
                <span className="small muted">{formatBytes(a.sizeBytes)}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {closed ? (
        <div className="notice notice-info">
          This ticket is closed. If you still need help, please open a new ticket.
        </div>
      ) : (
        <form className="card" onSubmit={onReply}>
          <div className="field">
            <label htmlFor="reply">Your reply</label>
            <textarea id="reply" rows={4} maxLength={5000} value={reply} onChange={(e) => setReply(e.target.value)} required />
          </div>
          <button className="btn btn-primary" disabled={busy || !reply.trim()}>{busy ? 'Sending…' : 'Send reply'}</button>
        </form>
      )}
    </div>
  );
}
