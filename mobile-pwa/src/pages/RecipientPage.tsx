import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isNetworkError, openBlob } from '../api/client';
import { recipientApi } from '../api/endpoints';
import type { HandoffItem } from '../api/types';
import { OfflineBanner } from '../components/AppShell';
import { useConfirm } from '../components/ConfirmSheet';
import { errorMessage, ErrorNotice, Spinner, StatusBadge } from '../components/ui';
import { CONDITION_LABELS, formatBytes, formatDateTime, isFinished, qty } from '../lib/format';

/**
 * What the recipient's secure link opens: one handoff, no sign-in (the token in the address is the proof, and it opens exactly this
 * handoff). The recipient reviews it, then acknowledges or declines; later they may confirm items reported missing. The wording is
 * a typed acknowledgement, never an e-signature.
 */
export function RecipientPage() {
  const { token } = useParams();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const [ackName, setAckName] = useState('');
  const [reason, setReason] = useState('');
  const [reasonInvalid, setReasonInvalid] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fileBusy, setFileBusy] = useState<number | null>(null);
  const reasonRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  const view = useQuery({ queryKey: ['recipient', token], queryFn: () => recipientApi.view(token!), retry: false });
  const refresh = () => qc.invalidateQueries({ queryKey: ['recipient', token] });

  // A refusal can appear far above the button that was pressed (an expired link, a handoff that has moved on): bring it into view.
  useEffect(() => {
    if (error && !reasonInvalid) errorRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [error, reasonInvalid]);

  const done = { onSuccess: () => { setError(null); void refresh(); }, onError: (e: unknown) => setError(errorMessage(e)) };
  const accept = useMutation({ mutationFn: () => recipientApi.accept(token!, ackName.trim()), ...done });
  const reject = useMutation({ mutationFn: () => recipientApi.reject(token!, ackName.trim(), reason.trim()), ...done });
  const confirmMissing = useMutation({ mutationFn: (name: string) => recipientApi.confirmMissing(token!, name), ...done });
  const returnWait = useMutation({
    mutationFn: (p: { name: string; reason: string }) => recipientApi.requestReturnWait(token!, p.name, p.reason), ...done,
  });

  async function onDecline() {
    if (reason.trim() === '') {
      setError('Please give a reason for declining.');
      setReasonInvalid(true);
      reasonRef.current?.focus();
      return;
    }
    const res = await confirm({ title: 'Decline this handoff?', message: 'The sender will be told that you declined.', danger: true, confirmText: 'Decline' });
    if (res.confirmed) reject.mutate();
  }

  async function onOpenFile(a: { id: number; originalFilename: string }) {
    setFileBusy(a.id);
    try {
      const { blob, filename } = await recipientApi.downloadAttachment(token!, a.id, a.originalFilename);
      openBlob(blob, filename);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setFileBusy(null);
    }
  }

  async function onConfirmMissing() {
    const res = await confirm({
      title: 'Confirm missing items?',
      message: 'You confirm these items were not returned. This lets the sender close the handoff.',
      confirmText: 'Confirm',
      input: { label: 'Type your full name to confirm', placeholder: 'Your name', required: true },
    });
    if (res.confirmed) confirmMissing.mutate(res.value);
  }

  async function onRequestWait() {
    const why = await confirm({
      title: 'Ask to wait for the return?',
      message: 'You are asking for more time to return the items. A reason is required.',
      confirmText: 'Next',
      input: { label: 'Why you need more time', placeholder: 'e.g. Items are being shipped back', required: true },
    });
    if (!why.confirmed) return;
    const who = await confirm({
      title: 'Type your name to confirm',
      message: 'Your typed name shows who made this request.',
      confirmText: 'Send request',
      input: { label: 'Your full name', placeholder: 'Your name', required: true },
    });
    if (who.confirmed) returnWait.mutate({ name: who.value, reason: why.value });
  }

  if (view.isLoading) return <div className="boot"><Spinner label="Opening your handoff…" /></div>;
  if (view.isError && isNetworkError(view.error)) {
    // Not the link's fault: the phone is offline or HandOffly is down.
    return (
      <main className="auth-page">
        <div className="card auth-card center">
          <div className="brand brand-lg">Hand<span>Offly</span></div>
          <h1>Can’t reach HandOffly</h1>
          <p className="muted">Check your connection, then try again. Your link is fine.</p>
          <button className="btn btn-primary btn-block" onClick={() => void view.refetch()}>Try again</button>
        </div>
      </main>
    );
  }
  if (view.isError) {
    return (
      <main className="auth-page">
        <div className="card auth-card center">
          <div className="brand brand-lg">Hand<span>Offly</span></div>
          <h1>This link can’t be opened</h1>
          <ErrorNotice error={view.error} />
          <p className="muted small">The link may have expired or be wrong. Please ask the sender to send you a new one.</p>
        </div>
      </main>
    );
  }

  const h = view.data!;
  const accepted = !!h.acknowledgementName && !h.awaitingResponse && h.status !== 'REJECTED';
  const totalReturned = h.items.reduce((s, i) => s + Number(i.returnedConfirmed || 0), 0);
  const totalMissing = h.items.reduce((s, i) => s + Number(i.missing || 0), 0);

  return (
    <div className="app recipient">
      <header className="topbar"><span className="brand">Hand<span>Offly</span></span></header>
      <OfflineBanner />
      <main className="page">
        <div className="stack">
          <div ref={errorRef} aria-live="assertive">{error && <div className="notice notice-error" role="alert">{error}</div>}</div>

          <section className="card handoff-head">
            <div className="spread"><span className="handoff-ref">{h.publicCode}</span><StatusBadge status={h.status} /></div>
            <h1>{h.title}</h1>
            <dl className="meta">
              <div><dt>From</dt><dd>{h.senderName}{h.senderOrganization ? ` · ${h.senderOrganization}` : ''}</dd></div>
              <div><dt>To</dt><dd>{h.recipientName}</dd></div>
              {h.outgoingAt && <div><dt>Sent</dt><dd>{formatDateTime(h.outgoingAt)}</dd></div>}
              {h.dueAt && <div><dt>Expected return</dt><dd>{formatDateTime(h.dueAt)}</dd></div>}
              {h.purpose && <div><dt>Purpose</dt><dd>{h.purpose}</dd></div>}
            </dl>
          </section>

          {h.awaitingResponse && <div className="notice notice-info">Please review the items below, then acknowledge receipt or decline at the bottom of the page.</div>}

          <section className="card">
            <div className="spread"><h2>Items handed to you</h2><span className="muted small">{h.items.length} {h.items.length === 1 ? 'item' : 'items'}</span></div>
            <ul className="stack-sm">{h.items.map((i) => <li key={i.id}><RecipientItem item={i} progress={accepted} /></li>)}</ul>
          </section>

          {h.attachments.length > 0 && (
            <section className="card">
              <h2>Attached documents</h2>
              <div className="stack-sm">
                {h.attachments.map((a) => (
                  <button key={a.id} className="btn btn-block attachment" disabled={fileBusy !== null} onClick={() => void onOpenFile(a)}>
                    <span className="attachment-name">{fileBusy === a.id ? 'Opening…' : a.originalFilename}</span>
                    <span className="muted small">{a.kind === 'REFERENCE_DOCUMENT' ? 'Reference' : 'Evidence'} · {formatBytes(a.sizeBytes)}</span>
                  </button>
                ))}
              </div>
            </section>
          )}

          {h.awaitingResponse && (
            <section className="card">
              <h2>Review &amp; acknowledge</h2>
              <p className="muted small">Type your name to acknowledge receipt. This is a typed acknowledgement, not a legally binding electronic signature.</p>
              <div className="field">
                <label htmlFor="ack-name">Your name</label>
                <input id="ack-name" autoComplete="name" value={ackName} onChange={(e) => setAckName(e.target.value)} placeholder="e.g. Priya Sharma" />
              </div>
              <div className="field">
                <label htmlFor="decline-reason">Reason for declining <span className="muted">(only if you decline)</span></label>
                <input id="decline-reason" ref={reasonRef} value={reason} aria-invalid={reasonInvalid}
                       aria-describedby={reasonInvalid ? 'decline-reason-error' : undefined}
                       onChange={(e) => { setReason(e.target.value); if (reasonInvalid) { setReasonInvalid(false); setError(null); } }}
                       placeholder="e.g. The quantity does not match" />
                {reasonInvalid && <div id="decline-reason-error" className="form-error">A reason is required to decline.</div>}
              </div>
              <div className="stack-sm">
                <button className="btn btn-primary btn-block" disabled={accept.isPending || ackName.trim() === ''} onClick={() => accept.mutate()}>
                  {accept.isPending ? 'Accepting…' : 'Accept & acknowledge'}
                </button>
                <button className="btn btn-danger btn-block" disabled={reject.isPending || ackName.trim() === ''} onClick={() => void onDecline()}>
                  {reject.isPending ? 'Declining…' : 'Decline'}
                </button>
              </div>
              {ackName.trim() === '' && <p className="muted small mt-1">Enter your name to accept or decline.</p>}
            </section>
          )}

          {h.acknowledgementName && !h.awaitingResponse && (
            <section className="card">
              <div className={`notice ${h.status === 'REJECTED' ? 'notice-error' : 'notice-success'}`} role="status">
                {h.status === 'REJECTED' ? 'Declined' : 'Acknowledged'} by {h.acknowledgementName} on {formatDateTime(h.acknowledgedAt)}.
              </div>
              {h.status === 'REJECTED' && h.rejectionReason && <dl className="kv mt-1"><dt>Reason for declining</dt><dd>{h.rejectionReason}</dd></dl>}
            </section>
          )}

          {h.missingToConfirm && (
            <section className="card">
              <h2>Confirm missing items</h2>
              <p className="muted small">
                The sender reports these item(s) as missing (not returned). Please confirm so the handoff can be closed, or ask to wait if you plan to return them.
              </p>
              <ul className="lines">{h.missingItems.map((m, n) => <li key={n}><span>{m.itemName}</span><strong>{qty(m.quantity)}</strong></li>)}</ul>
              <div className="stack-sm mt-1">
                <button className="btn btn-primary btn-block" disabled={confirmMissing.isPending || returnWait.isPending} onClick={() => void onConfirmMissing()}>
                  {confirmMissing.isPending ? 'Confirming…' : 'Confirm missing items'}
                </button>
                <button className="btn btn-block" disabled={confirmMissing.isPending || returnWait.isPending} onClick={() => void onRequestWait()}>
                  {returnWait.isPending ? 'Sending…' : 'Ask to wait for the return'}
                </button>
              </div>
            </section>
          )}
          {!isFinished(h.status) && h.returnWaitRequestedAt && !h.missingConfirmedAt && (
            <div className="notice notice-warning" role="status">
              You asked to wait for the return on {formatDateTime(h.returnWaitRequestedAt)}
              {h.returnWaitRequestedByName && <> — by <strong>{h.returnWaitRequestedByName}</strong></>}.
              <div className="small muted">Reason: “{h.returnWaitReason}” (typed acknowledgement, not an e-signature)</div>
            </div>
          )}
          {h.missingConfirmedAt && !h.missingToConfirm && (
            <div className="notice notice-success" role="status">
              You confirmed the missing items on {formatDateTime(h.missingConfirmedAt)}
              {h.missingConfirmedByName && <> — acknowledged by <strong>{h.missingConfirmedByName}</strong></>}.
              <div className="small muted">(Typed acknowledgement, not an e-signature.)</div>
            </div>
          )}

          {accepted && (
            <section className="card">
              <h2>Return progress</h2>
              <div className="tiles">
                <div className="tile"><div className="tile-n">{qty(h.totalOutgoing)}</div><div className="tile-l">Handed to you</div></div>
                <div className="tile"><div className="tile-n">{qty(totalReturned)}</div><div className="tile-l">Returned</div></div>
                <div className="tile"><div className={`tile-n ${totalMissing > 0 ? 'neg' : ''}`}>{qty(totalMissing)}</div><div className="tile-l">Missing</div></div>
                <div className="tile"><div className={`tile-n ${Number(h.totalRemaining) > 0 ? 'open' : 'zero'}`}>{qty(h.totalRemaining)}</div><div className="tile-l">Still to return</div></div>
              </div>
              <p className="muted small mt-1">Returns are recorded by the sender when items come back.</p>
              {h.returns.length > 0 && (
                <ul className="stack-sm mt-1">
                  {h.returns.map((r, n) => (
                    <li key={r.id} className="inner">
                      <div className="small"><strong>Return {n + 1}</strong> <span className="muted">· {formatDateTime(r.occurredAt)}</span></div>
                      {r.note && <p className="small">{r.note}</p>}
                      <ul className="lines">
                        {r.lines.map((l, idx) => <li key={idx}><span>{l.itemName}</span><span><strong>{qty(l.quantity)}</strong> <span className="muted small">({CONDITION_LABELS[l.condition]})</span></span></li>)}
                      </ul>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}

          <p className="center muted small">Powered by HandOffly</p>
        </div>
      </main>
    </div>
  );
}

function RecipientItem({ item: i, progress }: { item: HandoffItem; progress: boolean }) {
  return (
    <div className="inner">
      <div className="spread"><strong>{i.name}</strong>{i.unit && <span className="muted small">{i.unit}</span>}</div>
      <dl className="figures">
        <div><dt>Given</dt><dd>{qty(i.outgoing)}</dd></div>
        {progress && <>
          <div><dt>Returned</dt><dd>{qty(i.returnedConfirmed)}</dd></div>
          <div><dt>Missing</dt><dd className={Number(i.missing) > 0 ? 'neg' : ''}>{qty(i.missing)}</dd></div>
          <div><dt>Still to return</dt><dd className={Number(i.remaining) > 0 ? 'open' : 'zero'}>{qty(i.remaining)}</dd></div>
        </>}
      </dl>
    </div>
  );
}
