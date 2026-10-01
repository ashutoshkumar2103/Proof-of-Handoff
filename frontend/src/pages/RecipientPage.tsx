import { useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { recipientApi } from '../api/endpoints';
import type { RecipientView } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { ItemsTable } from '../components/ItemsTable';
import { ErrorNotice, Spinner, errorMessage } from '../components/ui';
import { ThemeToggle } from '../components/ThemeToggle';
import { useConfirm } from '../components/ConfirmDialog';
import { CONDITION_LABELS, formatDateTime, formatBytes, qty } from '../lib/format';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export function RecipientPage() {
  const { token } = useParams();
  const qc = useQueryClient();
  const [ackName, setAckName] = useState('');
  const [reason, setReason] = useState('');
  const [reasonInvalid, setReasonInvalid] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reasonRef = useRef<HTMLInputElement>(null);
  const confirm = useConfirm();

  const view = useQuery({
    queryKey: ['recipient', token],
    queryFn: () => recipientApi.view(token!),
    retry: false,
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ['recipient', token] });

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

  if (view.isLoading) return <div className="auth-wrap"><Spinner label="Loading handoff…" /></div>;
  if (view.isError) {
    return (
      <div className="auth-wrap">
        <div className="card auth-card center">
          <div className="brand">Hand<span>Offly</span></div>
          <ErrorNotice error={view.error} />
          <p className="muted small mt-2">This link may have expired or is invalid.</p>
        </div>
      </div>
    );
  }
  const h = view.data as RecipientView;

  return (
    <div className="container container-narrow">
      <div className="row" style={{ justifyContent: 'flex-end', paddingTop: '1rem' }}><ThemeToggle /></div>
      <div className="center mb-2">
        <div className="brand" style={{ fontSize: '1.5rem', fontWeight: 700 }}>Hand<span style={{ color: 'var(--primary)' }}>Offly</span></div>
        <p className="muted small">Handoff review · {h.publicCode}</p>
      </div>

      {error && <div className="notice notice-error mb-2">{error}</div>}

      <div className="card">
        <div className="spread">
          <h1 style={{ marginBottom: 4 }}>{h.title}</h1>
          <StatusBadge status={h.status} />
        </div>
        <dl className="kv mt-2">
          <dt>From</dt><dd>{h.senderName}{h.senderOrganization ? ` · ${h.senderOrganization}` : ''}</dd>
          <dt>To</dt><dd>{h.recipientName}</dd>
          {h.purpose && <><dt>Purpose</dt><dd>{h.purpose}</dd></>}
          {h.dueAt && <><dt>Expected return</dt><dd>{formatDateTime(h.dueAt)}</dd></>}
        </dl>
      </div>

      <div className="card">
        <h2>Items handed to you</h2>
        <ItemsTable items={h.items} showPending={false} />
      </div>

      {h.attachments.length > 0 && (
        <div className="card">
          <h2>Attached documents</h2>
          <div className="stack">
            {h.attachments.map((a) => (
              <div key={a.id} className="spread" style={{ padding: '0.3rem 0' }}>
                <a href={`${API_BASE}/api/v1/r/${token}/attachments/${a.id}/content`} target="_blank" rel="noreferrer">
                  {a.originalFilename}
                </a>
                <span className="muted small">{a.kind === 'REFERENCE_DOCUMENT' ? 'Reference' : 'Evidence'} · {formatBytes(a.sizeBytes)}</span>
              </div>
            ))}
          </div>
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
            <label>Your name</label>
            <input value={ackName} onChange={(e) => setAckName(e.target.value)} placeholder="e.g. Priya Sharma" />
          </div>
          <div className="field">
            <label>Reason for declining <span className="muted">(required only if you decline)</span></label>
            <input ref={reasonRef} className={reasonInvalid ? 'input-error' : ''} value={reason}
                   onChange={(e) => { setReason(e.target.value); if (reasonInvalid) setReasonInvalid(false); }}
                   placeholder="e.g. Quantity does not match what we agreed" />
            {reasonInvalid && <div className="form-error">A reason is required to decline.</div>}
          </div>
          <div className="row">
            <button className="btn btn-primary" disabled={accept.isPending || ackName.trim() === ''}
                    onClick={() => accept.mutate()}>Accept & acknowledge</button>
            <button className="btn btn-danger" disabled={reject.isPending || ackName.trim() === ''}
                    onClick={onDecline}>Decline</button>
          </div>
        </div>
      )}

      {/* Acknowledged summary */}
      {h.acknowledgementName && !h.awaitingResponse && (
        <div className="card">
          <div className={`notice ${h.status === 'REJECTED' ? 'notice-error' : 'notice-success'}`}>
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
          <ul>
            {h.missingItems.map((m, i) => (
              <li key={i}>{m.itemName}: <strong>{qty(m.quantity)}</strong></li>
            ))}
          </ul>
          <div className="row">
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
      {h.returnWaitRequestedAt && !h.missingConfirmedAt && (
        <div className="notice notice-warning mb-2">
          You requested to wait for return on {formatDateTime(h.returnWaitRequestedAt)}
          {h.returnWaitRequestedByName && <> — by <strong>{h.returnWaitRequestedByName}</strong></>}.
          <div className="small muted">Reason: &ldquo;{h.returnWaitReason}&rdquo;</div>
          <div className="small muted">(Typed acknowledgement — not a legally binding e-signature.)</div>
        </div>
      )}
      {h.missingConfirmedAt && !h.missingToConfirm && (
        <div className="notice notice-success mb-2">
          You confirmed the missing items on {formatDateTime(h.missingConfirmedAt)}
          {h.missingConfirmedByName && <> — acknowledged by <strong>{h.missingConfirmedByName}</strong></>}.
          <div className="small muted">(Typed acknowledgement — not a legally binding e-signature.)</div>
        </div>
      )}

      {/* Return progress (read-only for the recipient — the sender records returns) */}
      {h.acknowledgementName && !h.awaitingResponse && h.status !== 'REJECTED' && (() => {
        const totalReturned = h.items.reduce((s, i) => s + Number(i.returnedConfirmed || 0), 0);
        const totalMissing = h.items.reduce((s, i) => s + Number(i.missing || 0), 0);
        return (
        <div className="card">
          <h2>Return progress</h2>
          <p className="muted small">
            <strong>{totalReturned}</strong> returned of {qty(h.totalOutgoing)} total
            {totalMissing > 0 && <>, <span style={{ color: 'var(--danger)' }}><strong>{totalMissing}</strong> missing</span></>}
            {Number(h.totalRemaining) > 0 && <>, <strong>{qty(h.totalRemaining)}</strong> still to return</>}.
            {' '}Returns are recorded by the sender when items come back.
          </p>
          {totalMissing > 0 && !h.missingConfirmedAt && (
            <p className="small" style={{ color: 'var(--danger)', margin: '0 0 0.5rem' }}>
              Note: {totalMissing} item(s) are reported missing (not returned) and are awaiting confirmation.
            </p>
          )}
          {h.returns.length > 0 && (
            <div className="stack mt-2">
              {h.returns.map((r) => (
                <div key={r.id} className="card" style={{ background: 'var(--surface-2)' }}>
                  <div className="small"><strong>{formatDateTime(r.occurredAt)}</strong></div>
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
