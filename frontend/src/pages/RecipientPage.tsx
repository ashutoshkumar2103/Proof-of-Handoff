import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { recipientApi } from '../api/endpoints';
import type { RecipientView } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { ItemsTable } from '../components/ItemsTable';
import { ErrorNotice, Spinner, errorMessage } from '../components/ui';
import { ThemeToggle } from '../components/ThemeToggle';
import { useConfirm } from '../components/ConfirmDialog';
import { CONDITION_LABELS, formatDateTime, formatBytes, isFinished, qty } from '../lib/format';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export function RecipientPage() {
  const { token } = useParams();
  const qc = useQueryClient();
  const [ackName, setAckName] = useState('');
  const [reason, setReason] = useState('');
  const [reasonInvalid, setReasonInvalid] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reasonRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);
  const confirm = useConfirm();

  const view = useQuery({
    queryKey: ['recipient', token],
    queryFn: () => recipientApi.view(token!),
    retry: false,
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ['recipient', token] });

  // A refusal from the server (an expired link, a handoff that has moved on) shows at the top of the page, which may be well above
  // the button that was pressed: bring it into view. A missing decline reason is shown beside its own field instead.
  // Only when the message itself changes — not when the reason field is edited afterwards.
  useEffect(() => {
    if (error && !reasonInvalid) errorRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error]);

  const accept = useMutation({
    mutationFn: () => recipientApi.accept(token!, ackName.trim()),
    onSuccess: () => { setError(null); refresh(); },
    onError: (e) => setError(errorMessage(e)),
  });
  const reject = useMutation({
    mutationFn: () => recipientApi.reject(token!, ackName.trim(), reason.trim()),
    onSuccess: () => { setError(null); refresh(); },
    onError: (e) => setError(errorMessage(e)),
  });
  const confirmMissing = useMutation({
    mutationFn: (name: string) => recipientApi.confirmMissing(token!, name),
    onSuccess: () => { setError(null); refresh(); },
    onError: (e) => setError(errorMessage(e)),
  });
  const requestReturnWait = useMutation({
    mutationFn: (params: { name: string; reason: string }) =>
      recipientApi.requestReturnWait(token!, params.name, params.reason),
    onSuccess: () => { setError(null); refresh(); },
    onError: (e) => setError(errorMessage(e)),
  });

  async function onDecline() {
    if (reason.trim() === '') {
      setError('Please provide a reason for declining.');
      setReasonInvalid(true);
      reasonRef.current?.focus();
      reasonRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    const res = await confirm({
      title: 'Decline this handoff?',
      message: 'The sender will be notified that you declined.',
      danger: true, confirmText: 'Decline',
    });
    if (res.confirmed) reject.mutate();
  }

  if (view.isLoading) return <div className="auth-wrap" role="status" aria-live="polite"><Spinner label="Loading handoff…" /></div>;
  if (view.isError) {
    return (
      <div className="auth-wrap">
        <div className="card auth-card center">
          <div className="brand">Hand<span>Offly</span></div>
          <h1 style={{ fontSize: '1.15rem', margin: '0.5rem 0 0.75rem' }}>This link can’t be opened</h1>
          <div role="alert"><ErrorNotice error={view.error} /></div>
          <p className="muted small mt-2">This link may have expired or is invalid. Please ask the sender to send you a new link.</p>
        </div>
      </div>
    );
  }
  const h = view.data as RecipientView;
  // The handoff has been accepted (not declined): from here the recipient sees what is back, what is missing and what is still to return.
  const accepted = !!h.acknowledgementName && !h.awaitingResponse && h.status !== 'REJECTED';

  return (
    <div className="container recipient-page">
      <div className="spread recipient-top">
        <div className="recipient-brand">Hand<span>Offly</span></div>
        <ThemeToggle />
      </div>

      <div ref={errorRef} aria-live="assertive">
        {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      </div>

      {/* What is this handoff, who is it from, and where does it stand? */}
      <div className="card handoff-head">
        <div className="row">
          <span className="handoff-ref">{h.publicCode}</span>
          {h.category && <span className="badge">{h.category}</span>}
          <StatusBadge status={h.status} />
        </div>
        <h1>{h.title}</h1>
        <dl className="handoff-meta">
          <div><dt>From</dt><dd>{h.senderName}{h.senderOrganization ? ` · ${h.senderOrganization}` : ''}</dd></div>
          <div><dt>To</dt><dd>{h.recipientName}</dd></div>
          {h.outgoingAt && <div><dt>Sent</dt><dd>{formatDateTime(h.outgoingAt)}</dd></div>}
          {h.dueAt && <div><dt>Expected return</dt><dd>{formatDateTime(h.dueAt)}</dd></div>}
          {h.purpose && <div className="wide"><dt>Purpose</dt><dd>{h.purpose}</dd></div>}
        </dl>
      </div>

      {h.awaitingResponse && (
        <div className="notice notice-info">
          Please review the items below, then acknowledge receipt or decline at the bottom of the page.
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2 style={{ margin: 0 }}>Items handed to you</h2>
          <span className="muted small">{h.items.length} {h.items.length === 1 ? 'item' : 'items'}</span>
        </div>
        <ItemsTable items={h.items} showPending={false} compact showProgress={accepted} />
      </div>

      {h.attachments.length > 0 && (
        <div className="card">
          <h2>Attached documents</h2>
          <ul className="recipient-docs">
            {h.attachments.map((a) => (
              <li key={a.id}>
                <a href={`${API_BASE}/api/v1/r/${token}/attachments/${a.id}/content`} target="_blank" rel="noreferrer">
                  {a.originalFilename}
                </a>
                <span className="muted small">{a.kind === 'REFERENCE_DOCUMENT' ? 'Reference' : 'Evidence'} · {formatBytes(a.sizeBytes)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Accept / Reject */}
      {h.awaitingResponse && (
        <div className="card">
          <h2>Review & acknowledge</h2>
          <p className="muted small">
            Type your name below to acknowledge receipt. This is a typed acknowledgement, not a legally
            binding electronic signature.
          </p>
          <div className="field">
            <label htmlFor="ack-name">Your name</label>
            <input id="ack-name" autoComplete="name" value={ackName} onChange={(e) => setAckName(e.target.value)} placeholder="e.g. Priya Sharma" />
          </div>
          <div className="field">
            <label htmlFor="decline-reason">Reason for declining <span className="muted">(required only if you decline)</span></label>
            <input id="decline-reason" ref={reasonRef} className={reasonInvalid ? 'input-error' : ''} value={reason}
                   aria-invalid={reasonInvalid} aria-describedby={reasonInvalid ? 'decline-reason-error' : undefined}
                   onChange={(e) => { setReason(e.target.value); if (reasonInvalid) { setReasonInvalid(false); setError(null); } }}
                   placeholder="e.g. Quantity does not match what we agreed" />
            {reasonInvalid && <div id="decline-reason-error" className="form-error">A reason is required to decline.</div>}
          </div>
          <div className="recipient-actions">
            <button className="btn btn-primary" disabled={accept.isPending || ackName.trim() === ''}
                    onClick={() => accept.mutate()}>{accept.isPending ? 'Accepting…' : 'Accept & acknowledge'}</button>
            <button className="btn btn-danger" disabled={reject.isPending || ackName.trim() === ''}
                    onClick={onDecline}>{reject.isPending ? 'Declining…' : 'Decline'}</button>
          </div>
          {ackName.trim() === '' && <p className="muted small" style={{ margin: '0.75rem 0 0' }}>Enter your name to accept or decline.</p>}
        </div>
      )}

      {/* Acknowledged summary */}
      {h.acknowledgementName && !h.awaitingResponse && (
        <div className="card">
          <div className={`notice ${h.status === 'REJECTED' ? 'notice-error' : 'notice-success'}`} role="status">
            {h.status === 'REJECTED'
              ? <>Declined by {h.acknowledgementName} on {formatDateTime(h.acknowledgedAt)}.</>
              : <>Acknowledged by {h.acknowledgementName} on {formatDateTime(h.acknowledgedAt)}.</>}
          </div>
          {h.status === 'REJECTED' && h.rejectionReason && (
            <dl className="kv mt-2">
              <dt>Reason for declining</dt>
              <dd>{h.rejectionReason}</dd>
            </dl>
          )}
        </div>
      )}

      {/* Confirm missing items */}
      {h.missingToConfirm && (
        <div className="card">
          <h2>Confirm missing items</h2>
          <p className="muted small">
            The sender reports the following item(s) as missing (not returned). Please review and confirm
            so the handoff can be closed — or request to wait if you plan to return the items.
          </p>
          <ul className="recipient-missing">
            {h.missingItems.map((m, i) => (
              <li key={i}>{m.itemName}: <strong>{qty(m.quantity)}</strong></li>
            ))}
          </ul>
          <div className="recipient-actions">
            <button className="btn btn-primary" disabled={confirmMissing.isPending || requestReturnWait.isPending}
                    onClick={async () => {
                      const res = await confirm({
                        title: 'Confirm missing items?',
                        message: 'You confirm these items were not returned. This lets the sender close the handoff.',
                        confirmText: 'Confirm',
                        input: { label: 'Type your full name to confirm', placeholder: 'Your name', required: true },
                      });
                      if (res.confirmed) confirmMissing.mutate(res.value);
                    }}>
              {confirmMissing.isPending ? 'Confirming…' : 'Confirm missing items'}
            </button>
            <button className="btn btn-warning" disabled={confirmMissing.isPending || requestReturnWait.isPending}
                    onClick={async () => {
                      const res = await confirm({
                        title: 'Request to wait for return?',
                        message: 'You are requesting additional time to return the items. A reason is required.',
                        confirmText: 'Send request',
                        input: { label: 'Reason (required) — why you need more time', placeholder: 'e.g. Items are being shipped back, expected next week', required: true },
                      });
                      if (!res.confirmed) return;
                      const waitReason = res.value;
                      const nameRes = await confirm({
                        title: 'Type your name to confirm',
                        message: 'Your name serves as proof of who made this request.',
                        confirmText: 'Submit',
                        input: { label: 'Your full name', placeholder: 'Your name', required: true },
                      });
                      if (nameRes.confirmed) requestReturnWait.mutate({ name: nameRes.value, reason: waitReason });
                    }}>
              {requestReturnWait.isPending ? 'Sending…' : 'Request to wait for return'}
            </button>
          </div>
        </div>
      )}
      {/* A pending request is only worth showing while the handoff is still open. */}
      {!isFinished(h.status) && h.returnWaitRequestedAt && !h.missingConfirmedAt && (
        <div className="notice notice-warning" role="status">
          You requested to wait for return on {formatDateTime(h.returnWaitRequestedAt)}
          {h.returnWaitRequestedByName && <> — by <strong>{h.returnWaitRequestedByName}</strong></>}.
          <div className="small muted">Reason: &ldquo;{h.returnWaitReason}&rdquo;</div>
          <div className="small muted">(Typed acknowledgement — not a legally binding e-signature.)</div>
        </div>
      )}
      {h.missingConfirmedAt && !h.missingToConfirm && (
        <div className="notice notice-success" role="status">
          You confirmed the missing items on {formatDateTime(h.missingConfirmedAt)}
          {h.missingConfirmedByName && <> — acknowledged by <strong>{h.missingConfirmedByName}</strong></>}.
          <div className="small muted">(Typed acknowledgement — not a legally binding e-signature.)</div>
        </div>
      )}

      {/* Return progress (read-only for the recipient — the sender records returns) */}
      {accepted && (() => {
        const totalReturned = h.items.reduce((s, i) => s + Number(i.returnedConfirmed || 0), 0);
        const totalMissing = h.items.reduce((s, i) => s + Number(i.missing || 0), 0);
        return (
        <div className="card">
          <h2>Return progress</h2>
          <div className="stat-grid recipient-tiles">
            <div className="stat static"><div className="n">{qty(h.totalOutgoing)}</div><div className="l">Handed to you</div></div>
            <div className="stat static"><div className="n">{qty(String(totalReturned))}</div><div className="l">Returned</div></div>
            <div className="stat static"><div className="n" style={totalMissing > 0 ? { color: 'var(--danger)' } : undefined}>{qty(String(totalMissing))}</div><div className="l">Missing</div></div>
            <div className="stat static"><div className={`n ${Number(h.totalRemaining) > 0 ? 'remaining-open' : 'remaining-zero'}`}>{qty(h.totalRemaining)}</div><div className="l">Still to return</div></div>
          </div>
          <p className="muted small" style={{ margin: '0.75rem 0 0' }}>Returns are recorded by the sender when items come back.</p>
          {!isFinished(h.status) && totalMissing > 0 && !h.missingConfirmedAt && (
            <div className="notice notice-error mt-2">
              Note: {totalMissing} item(s) are reported missing (not returned) and are awaiting confirmation.
            </div>
          )}
          {h.returns.length > 0 && (
            <div className="stack mt-2">
              {h.returns.map((r, n) => (
                <div key={r.id} className="recipient-return">
                  <div className="small"><strong>Return {n + 1}</strong> <span className="muted">· {formatDateTime(r.occurredAt)}</span></div>
                  {r.note && <p className="small" style={{ margin: '0.3rem 0' }}>{r.note}</p>}
                  <ul className="small" style={{ margin: '0.3rem 0 0', paddingLeft: '1.1rem' }}>
                    {r.lines.map((l, idx) => (
                      <li key={idx}>{l.itemName}: <strong>{qty(l.quantity)}</strong>{' '}
                        <span className="muted">({CONDITION_LABELS[l.condition] ?? l.condition})</span></li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </div>
        );
      })()}

      <p className="center muted small mt-3">Powered by HandOffly</p>
    </div>
  );
}
