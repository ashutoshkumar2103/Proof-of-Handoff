import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi, supportMessageApi, ticketApi } from '../api/endpoints';
import type { TicketCategory } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../components/ui';
import { TICKET_CATEGORY_LABELS } from '../lib/format';

const CATEGORIES = Object.keys(TICKET_CATEGORY_LABELS) as TicketCategory[];

/**
 * The form for contacting support: a full ticket (plans with tickets) or a plain message (plans with only
 * Contact Support — no category or phone, and nothing to follow in the app afterwards). Who is asking comes
 * from the signed-in account (shown read-only); the customer only writes the request.
 */
export function NewTicketPage({ mode = 'ticket' }: { mode?: 'ticket' | 'message' }) {
  const isMessage = mode === 'message';
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    subject: '', category: 'GENERAL' as TicketCategory, description: '', handoffReference: '', phone: user?.phone ?? '',
  });
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // The customer's own handoff references, offered as suggestions (typing any reference is fine too).
  const handoffs = useQuery({
    queryKey: ['handoffs', 'for-ticket'],
    queryFn: () => handoffApi.list({ size: 50, sort: 'id,desc' }),
    staleTime: 60_000,
  });

  const set = (k: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setForm({ ...form, [k]: e.target.value });

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    if (isMessage) {
      try {
        const sent = await supportMessageApi.send({
          subject: form.subject, message: form.description,
          handoffReference: form.handoffReference || undefined, file,
        });
        navigate('/support', { state: { notice: `Thank you — your message was sent. Reference ${sent.reference}. We will reply by email.` } });
      } catch (err) {
        setError(errorMessage(err));
        setBusy(false);
      }
      return;
    }
    let code: string;
    try {
      const created = await ticketApi.create({
        subject: form.subject, category: form.category, description: form.description,
        handoffReference: form.handoffReference || undefined, phone: form.phone || undefined,
      });
      code = created.ticket.ticketCode;
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
      return;
    }
    // The ticket exists now; a file that cannot be attached must not make it look as if it was not created.
    let notice: string | undefined;
    if (file) {
      try {
        await ticketApi.upload(code, file);
      } catch (err) {
        notice = `Your ticket ${code} was created, but the file could not be attached: ${errorMessage(err)}`;
      }
    }
    await queryClient.invalidateQueries({ queryKey: ['tickets'] });
    navigate(`/support/tickets/${code}`, { state: { notice } });
  }

  return (
    <div className="stack page-narrow">
      <div>
        <Link to="/support" className="small">← Contact Support</Link>
        <h1>{isMessage ? 'Send a message' : 'Create a ticket'}</h1>
        <p className="muted">
          {isMessage ? 'Tell us what you need. We will reply by email.' : 'Tell us what you need. We will reply by email and in this ticket.'}
        </p>
      </div>

      <form className="card" onSubmit={onSubmit}>
        {error && <div className="notice notice-error mb-2">{error}</div>}

        <div className="field-row">
          <div className="field">
            <label htmlFor="accountCode">Account ID</label>
            <input id="accountCode" className="readonly-field" value={user?.accountCode ?? ''} readOnly />
          </div>
          <div className="field">
            <label htmlFor="name">Name</label>
            <input id="name" className="readonly-field" value={user?.displayName ?? ''} readOnly />
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" className="readonly-field" value={user?.email ?? ''} readOnly />
          </div>
          {!isMessage && (
            <div className="field">
              <label htmlFor="phone">Phone <span className="muted">(optional)</span></label>
              <input id="phone" type="tel" maxLength={40} value={form.phone} onChange={set('phone')} />
            </div>
          )}
        </div>

        <div className="divider" />

        <div className="field">
          <label htmlFor="subject">Subject</label>
          <input id="subject" maxLength={200} value={form.subject} onChange={set('subject')} required />
        </div>
        <div className="field-row">
          {!isMessage && (
            <div className="field">
              <label htmlFor="category">What is it about?</label>
              <select id="category" value={form.category} onChange={set('category')}>
                {CATEGORIES.map((c) => <option key={c} value={c}>{TICKET_CATEGORY_LABELS[c]}</option>)}
              </select>
            </div>
          )}
          <div className="field">
            <label htmlFor="handoffReference">Related handoff <span className="muted">(optional)</span></label>
            <input id="handoffReference" list="handoff-references" maxLength={20} placeholder="e.g. HO-12"
                   value={form.handoffReference} onChange={set('handoffReference')} />
            <datalist id="handoff-references">
              {(handoffs.data?.content ?? []).map((h) => <option key={h.id} value={h.publicCode}>{h.title}</option>)}
            </datalist>
          </div>
        </div>
        <div className="field">
          <label htmlFor="description">{isMessage ? 'Your message' : 'Describe your request'}</label>
          <textarea id="description" rows={6} maxLength={5000} value={form.description}
                    onChange={set('description')} required />
        </div>
        <div className="field">
          <label htmlFor="attachment">Attachment <span className="muted">(optional)</span></label>
          <input id="attachment" type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </div>

        <div className="row">
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Sending…' : isMessage ? 'Send message' : 'Send ticket'}</button>
          <Link to="/support" className="btn btn-ghost">Cancel</Link>
        </div>
      </form>
    </div>
  );
}
