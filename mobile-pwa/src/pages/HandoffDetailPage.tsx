import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isSubscriptionRefusal, openBlob } from '../api/client';
import { handoffApi, returnApi } from '../api/endpoints';
import type { Attachment, HandoffAction, HandoffDetail, HandoffItem } from '../api/types';
import { useConfirm } from '../components/ConfirmSheet';
import { errorMessage, ErrorNotice, Spinner, StatusBadge, Toast } from '../components/ui';
import { CONDITION_LABELS, formatBytes, formatDate, formatDateTime, isFinished, qty, REFERENCE_PENDING, SUBSCRIPTION_ENDED_MESSAGE } from '../lib/format';
import { useTransient } from '../lib/useTransient';

interface Arrival { notice?: string; problem?: string }

export function HandoffDetailPage() {
  const handoffId = Number(useParams().id);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const location = useLocation();
  const arrival = location.state as Arrival | null;
  const [notice, setNotice] = useTransient<string>();
  const [problem, setProblem] = useState<string | null>(arrival?.problem ?? null);
  const [busyFile, setBusyFile] = useState<string | null>(null);

  // What the page before said (e.g. "Draft saved.") is shown once, then forgotten, so a refresh does not repeat it.
  useEffect(() => {
    if (arrival?.notice) setNotice(arrival.notice);
    if (arrival) navigate(location.pathname, { replace: true, state: null });
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  const detail = useQuery({
    queryKey: ['handoff', handoffId],
    queryFn: () => handoffApi.get(handoffId),
    // While waiting on the recipient, look again every few seconds so their answer shows without a refresh.
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s === 'AWAITING_RECIPIENT' || s === 'OUTGOING_SENT' ? 5000 : false;
    },
  });

  function refresh() {
    void qc.invalidateQueries({ queryKey: ['handoff', handoffId] });
    void qc.invalidateQueries({ queryKey: ['dashboard'] });
    void qc.invalidateQueries({ queryKey: ['handoffs'] });
  }

  const action = useMutation({
    mutationFn: ({ run }: { run: () => Promise<HandoffDetail>; done?: string }) => run(),
    onSuccess: (_d, vars) => { setProblem(null); if (vars.done) setNotice(vars.done); refresh(); },
    onError: (err) => setProblem(isSubscriptionRefusal(err) ? SUBSCRIPTION_ENDED_MESSAGE : errorMessage(err)),
  });

  const confirmReturn = useMutation({
    mutationFn: (returnId: number) => returnApi.confirm(handoffId, returnId),
    onSuccess: () => { setProblem(null); setNotice('Return confirmed.'); refresh(); },
    onError: (err) => setProblem(errorMessage(err)),
  });

  if (detail.isLoading) return <Spinner />;
  if (detail.isError) return <ErrorNotice error={detail.error} onRetry={() => void detail.refetch()} />;
  const h = detail.data!;
  const has = (a: HandoffAction) => h.availableActions.includes(a);
  const missingTotal = Number(h.totalMissing);

  async function download(key: string, fetchIt: () => Promise<{ blob: Blob; filename: string }>) {
    setProblem(null);
    setBusyFile(key);
    try {
      const { blob, filename } = await fetchIt();
      openBlob(blob, filename);
    } catch (err) {
      setProblem(errorMessage(err));
    } finally {
      setBusyFile(null);
    }
  }

  async function onCancel() {
    const res = await confirm({
      title: 'Cancel this handoff?', message: 'The recipient will no longer be able to act on it.',
      danger: true, confirmText: 'Cancel handoff', cancelText: 'Keep',
      input: { label: 'Reason (required)', placeholder: 'Why are you cancelling?', required: true },
    });
    if (res.confirmed) action.mutate({ run: () => handoffApi.cancel(handoffId, res.value), done: 'Handoff cancelled.' });
  }

  async function onDispute() {
    const res = await confirm({
      title: 'Mark as disputed?', confirmText: 'Mark disputed',
      input: { label: 'Reason (required)', placeholder: 'Describe the dispute', required: true },
    });
    if (res.confirmed) action.mutate({ run: () => handoffApi.dispute(handoffId, res.value), done: 'Marked as disputed.' });
  }

  async function onDelete() {
    const res = await confirm({ title: 'Delete draft handoff?', message: 'This cannot be undone.', danger: true, confirmText: 'Delete' });
    if (!res.confirmed) return;
    try {
      await handoffApi.remove(handoffId);
      qc.removeQueries({ queryKey: ['handoff', handoffId] });
      void qc.invalidateQueries({ queryKey: ['dashboard'] });
      void qc.invalidateQueries({ queryKey: ['handoffs'] });
      navigate('/', { replace: true, state: { notice: 'Draft deleted.' } });
    } catch (err) {
      setProblem(errorMessage(err));
    }
  }

  async function onClose() {
    // Everything back: close directly. Items left missing: closing is allowed, but needs a reason for finalising with them unreturned.
    if (missingTotal <= 0) {
      action.mutate({ run: () => handoffApi.close(handoffId), done: 'Handoff closed.' });
      return;
    }
    const res = await confirm({
      title: 'Close with missing items?',
      message: <>This handoff has <strong>{qty(h.totalMissing)}</strong> item(s) recorded as missing. Closing finalises it with those items unreturned.</>,
      confirmText: 'Close handoff', danger: true,
      input: { label: 'Reason (required)', placeholder: 'e.g. Items written off as lost', required: true },
    });
    if (res.confirmed) action.mutate({ run: () => handoffApi.close(handoffId, res.value), done: 'Handoff closed.' });
  }

  async function onSubmit() {
    const res = await confirm({
      title: 'Send to the recipient?',
      message: `${h.recipientName} (${h.recipientEmail}) will get a secure link to review and acknowledge these items.`,
      confirmText: 'Send link',
    });
    if (res.confirmed) action.mutate({ run: () => handoffApi.submit(handoffId), done: 'Sent. The recipient has their link.' });
  }

  const busy = action.isPending;
  const more = has('DISPUTE') || has('CANCEL') || has('DELETE');

  return (
    <div className="stack">
      <Link to="/" className="back">← Handoffs</Link>

      <section className="card handoff-head">
        <div className="spread">
          <span className="handoff-ref">{h.publicCode ?? REFERENCE_PENDING}</span>
          <span className="row row-tight">
            {h.overdue && <span className="badge badge-danger">Overdue</span>}
            <StatusBadge status={h.status} />
          </span>
        </div>
        <h1>{h.title}</h1>
        {h.purpose && <p className="muted">{h.purpose}</p>}
        <dl className="meta">
          <div><dt>Recipient</dt><dd>{h.recipientName}</dd></div>
          {h.dueAt && <div><dt>Expected return</dt><dd className={h.overdue ? 'neg' : undefined}>{formatDate(h.dueAt)}</dd></div>}
          {h.outgoingAt && <div><dt>Sent</dt><dd>{formatDate(h.outgoingAt)}</dd></div>}
        </dl>
      </section>

      {problem && (
        <div className="notice notice-error" role="alert">
          {problem}
          <button type="button" className="btn btn-sm btn-negative mt-1" onClick={() => setProblem(null)}>Dismiss</button>
        </div>
      )}
      {notice && <Toast tone="success">{notice}</Toast>}

      <section aria-label="Quantities" className="tiles">
        <Tile label="Given" value={qty(h.totalOutgoing)} />
        <Tile label="Returned" value={qty(h.totalReturned)} />
        <Tile label="Missing" value={qty(h.totalMissing)} tone={missingTotal > 0 ? 'neg' : undefined} />
        <Tile label="Remaining" value={qty(h.totalRemaining)} tone={Number(h.totalRemaining) > 0 ? 'open' : 'zero'} />
      </section>

      <MissingNotices h={h} />

      <section className="card">
        <h2>Next steps</h2>
        <div className="stack-sm">
          {has('SUBMIT') && <button className="btn btn-primary btn-block" disabled={busy} onClick={onSubmit}>Send link to recipient</button>}
          {has('RECORD_RETURN') && <Link className="btn btn-primary btn-block" to={`/h/${handoffId}/return`}>Record return</Link>}
          {has('REQUEST_MISSING_CONFIRMATION') && (
            <button className="btn btn-primary btn-block" disabled={busy}
                    onClick={() => action.mutate({ run: () => handoffApi.requestMissingConfirmation(handoffId), done: 'Confirmation link sent.' })}>
              {h.missingConfirmationRequestedAt ? 'Resend missing-confirmation link' : 'Request missing confirmation'}
            </button>
          )}
          {has('EDIT') && <Link className="btn btn-block" to={`/h/${handoffId}/edit`}>Edit draft</Link>}
          {has('RESEND_LINK') && (
            <button className="btn btn-block" disabled={busy}
                    onClick={() => action.mutate({ run: () => handoffApi.resendLink(handoffId), done: 'Link sent again.' })}>Resend link</button>
          )}
          {has('CLOSE') && <button className="btn btn-danger btn-block" disabled={busy} onClick={onClose}>Close handoff</button>}
          <Link className="btn btn-block" to={`/new?from=${handoffId}`}>Duplicate as a new draft</Link>
        </div>
        {h.availableActions.length === 0 && <p className="muted small mt-1">This handoff is closed and read-only.</p>}
        {more && (
          <details className="more">
            <summary>More actions</summary>
            <div className="stack-sm">
              {has('DISPUTE') && <button className="btn btn-block" disabled={busy} onClick={onDispute}>Mark disputed</button>}
              {has('CANCEL') && <button className="btn btn-danger btn-block" disabled={busy} onClick={onCancel}>Cancel handoff</button>}
              {has('DELETE') && <button className="btn btn-danger btn-block" onClick={onDelete}>Delete draft</button>}
            </div>
          </details>
        )}
      </section>

      <section className="card">
        <div className="spread"><h2>Items</h2><span className="muted small">{h.items.length} {h.items.length === 1 ? 'item' : 'items'}</span></div>
        <ul className="stack-sm">{h.items.map((i) => <li key={i.id}><ItemCard item={i} /></li>)}</ul>
      </section>

      <section className="card">
        <div className="spread">
          <h2>Return history</h2>
          {h.returns.length > 0 && <span className="muted small">{h.returns.length} {h.returns.length === 1 ? 'return' : 'returns'}</span>}
        </div>
        {h.returns.length === 0 ? <p className="muted">No returns recorded yet.</p> : (
          <ul className="stack-sm">
            {h.returns.map((r, n) => (
              <li key={r.id} className="inner">
                <div className="spread">
                  <strong>Return {n + 1}</strong>
                  {r.confirmed ? <span className="badge badge-success badge-dot">Confirmed</span> : <span className="badge badge-warning badge-dot">Pending confirmation</span>}
                </div>
                <p className="muted small">{formatDateTime(r.occurredAt)} · by {r.enteredByType === 'RECIPIENT' ? 'recipient' : r.enteredByRef ?? 'owner'}</p>
                {r.note && <p className="small">{r.note}</p>}
                <ul className="lines">
                  {r.lines.map((l) => (
                    <li key={l.id}>
                      <span>{l.itemName}</span>
                      <span><strong>{qty(l.quantity)}</strong> <span className={`badge ${l.condition === 'MISSING' ? 'badge-danger' : l.condition === 'DAMAGED' ? 'badge-warning' : ''}`}>{CONDITION_LABELS[l.condition]}</span></span>
                      {l.note && <span className="muted small full">{l.note}</span>}
                    </li>
                  ))}
                </ul>
                {!r.confirmed && has('CONFIRM_RETURN') && (
                  <button className="btn btn-sm btn-primary mt-1" disabled={confirmReturn.isPending} onClick={() => confirmReturn.mutate(r.id)}>Confirm this return</button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <h2>Documents</h2>
        <div className="stack-sm">
          <button className="btn btn-block" disabled={busyFile !== null} onClick={() => void download('pdf', () => handoffApi.downloadPdf(handoffId))}>
            {busyFile === 'pdf' ? 'Preparing PDF…' : 'Download Proof-of-Handoff PDF'}
          </button>
          {h.attachments.map((a) => <AttachmentRow key={a.id} a={a} busy={busyFile === `a${a.id}`} disabled={busyFile !== null}
            onOpen={() => void download(`a${a.id}`, () => handoffApi.downloadAttachment(handoffId, a.id, a.originalFilename))} />)}
        </div>
        {h.attachments.length === 0 && <p className="muted small mt-1">No attached documents.</p>}
      </section>

      <section className="card">
        <h2>Details</h2>
        <dl className="kv">
          <dt>Sender</dt><dd>{h.senderName}{h.senderOrganization ? ` · ${h.senderOrganization}` : ''}</dd>
          <dt>Recipient</dt><dd>{h.recipientName}<br />{h.recipientEmail}{h.recipientPhone ? <><br />{h.recipientPhone}</> : null}</dd>
          <dt>Created</dt><dd>{formatDateTime(h.createdAt)}</dd>
          <dt>Sent</dt><dd>{formatDateTime(h.outgoingAt)}</dd>
          <dt>Accepted</dt><dd>{formatDateTime(h.acceptanceAt)}</dd>
          {h.acknowledgementName && <><dt>Acknowledged by</dt><dd>{h.acknowledgementName} <span className="muted small">(typed acknowledgement)</span></dd></>}
          {h.rejectionReason && <><dt>Declined because</dt><dd>{h.rejectionReason}</dd></>}
        </dl>
      </section>

      <details className="card more">
        <summary>Event history ({h.events.length})</summary>
        <ul className="timeline">
          {h.events.map((e) => (
            <li key={e.id}>
              <div className="small"><strong>{e.message}</strong></div>
              <div className="small muted">{formatDateTime(e.at)} · {e.actorType.toLowerCase()}{e.actorRef ? ` · ${e.actorRef}` : ''}</div>
            </li>
          ))}
        </ul>
      </details>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: string; tone?: 'neg' | 'open' | 'zero' }) {
  return <div className="tile"><div className={`tile-n ${tone ?? ''}`}>{value}</div><div className="tile-l">{label}</div></div>;
}

/** Where the missing items stand, in words (open work only; once finished only the settled outcome is shown). */
function MissingNotices({ h }: { h: HandoffDetail }) {
  const missingTotal = Number(h.totalMissing);
  const finished = isFinished(h.status);
  return (
    <>
      {missingTotal > 0 && (!finished || h.missingConfirmedAt) && (
        <div className={`notice ${h.missingConfirmedAt ? (finished ? 'notice-error' : 'notice-success') : 'notice-info'}`}>
          {h.missingConfirmedAt
            ? <><strong>{qty(h.totalMissing)}</strong> reported missing — confirmed by {h.missingConfirmedByName ?? 'the recipient'} on {formatDateTime(h.missingConfirmedAt)}.{!finished && ' You can now close this handoff.'}</>
            : h.returnWaitRequestedAt
              ? <><strong>{qty(h.totalMissing)}</strong> reported missing — the recipient asked to wait for the return. You can resend the confirmation request.</>
              : h.missingConfirmationRequestedAt
                ? <><strong>{qty(h.totalMissing)}</strong> reported missing — waiting for the recipient to confirm before you can close.</>
                : <><strong>{qty(h.totalMissing)}</strong> reported missing. Ask the recipient to confirm before closing.</>}
        </div>
      )}
      {!finished && h.returnWaitRequestedAt && !h.missingConfirmedAt && (
        <div className="notice notice-warning">
          <strong>{h.returnWaitRequestedByName || 'The recipient'}</strong> asked to wait for the return on {formatDateTime(h.returnWaitRequestedAt)}:
          <blockquote>“{h.returnWaitReason}”</blockquote>
        </div>
      )}
    </>
  );
}

function ItemCard({ item: i }: { item: HandoffItem }) {
  const open = Number(i.remaining) > 0;
  return (
    <div className="inner">
      <div className="spread">
        <strong>{i.name}</strong>
        {i.unit && <span className="muted small">{i.unit}</span>}
      </div>
      {(i.serialNumber || i.notes) && <p className="muted small">{[i.serialNumber, i.notes].filter(Boolean).join(' · ')}</p>}
      <dl className="figures">
        <div><dt>Given</dt><dd>{qty(i.outgoing)}</dd></div>
        <div><dt>Returned</dt><dd>{qty(i.returnedConfirmed)}</dd></div>
        <div><dt>Missing</dt><dd className={Number(i.missing) > 0 ? 'neg' : ''}>{qty(i.missing)}</dd></div>
        <div><dt>Remaining</dt><dd className={open ? 'open' : 'zero'}>{qty(i.remaining)}</dd></div>
      </dl>
      {Number(i.returnedPending) > 0 && <p className="small muted">{qty(i.returnedPending)} returned, waiting for confirmation.</p>}
    </div>
  );
}

function AttachmentRow({ a, onOpen, busy, disabled }: { a: Attachment; onOpen: () => void; busy: boolean; disabled: boolean }) {
  return (
    <button className="btn btn-block attachment" disabled={disabled} onClick={onOpen}>
      <span className="attachment-name">{busy ? 'Opening…' : a.originalFilename}</span>
      <span className="muted small">{a.kind === 'REFERENCE_DOCUMENT' ? 'Reference' : 'Evidence'} · {formatBytes(a.sizeBytes)}</span>
    </button>
  );
}
