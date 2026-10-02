import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isSubscriptionEnded, saveBlob } from '../api/client';
import { handoffApi, returnApi } from '../api/endpoints';
import type { CreateReturnInput, HandoffAction, HandoffDetail, ReturnPrefill } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { ItemsTable } from '../components/ItemsTable';
import { ReturnForm } from '../components/ReturnForm';
import { AttachmentsPanel } from '../components/AttachmentsPanel';
import { ErrorNotice, Spinner, errorMessage, useSubscriptionEndedModal, useTransient } from '../components/ui';
import { useConfirm } from '../components/ConfirmDialog';
import { CONDITION_LABELS, formatDateTime, isFinished, qty } from '../lib/format';

export function HandoffDetailPage() {
  const { id } = useParams();
  const handoffId = Number(id);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const location = useLocation();
  // Quantities imported via HandoffCheck arrive once in navigation state and reopen the same return form.
  const [prefill, setPrefill] = useState<ReturnPrefill | null>(
    (location.state as { returnPrefill?: ReturnPrefill } | null)?.returnPrefill ?? null);
  const [showReturn, setShowReturn] = useState(prefill !== null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useTransient<{ text: string; tone: 'notice-success' | 'notice-info' }>();
  const [pdfBusy, setPdfBusy] = useState<'download' | 'share' | 'email' | null>(null);
  // A PDF already fetched for sharing, so a retry can share instantly while the user gesture is fresh.
  const sharePdf = useRef<{ version: string; file: File } | null>(null);
  const confirm = useConfirm();
  const showSubscriptionEnded = useSubscriptionEndedModal();

  useEffect(() => {
    if (prefill) navigate(location.pathname, { replace: true, state: null });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const detail = useQuery({
    queryKey: ['handoff', handoffId],
    queryFn: () => handoffApi.get(handoffId),
    // While waiting on the recipient, poll so the owner sees acceptance/rejection
    // promptly and stale actions (Cancel/Resend) update to the next step automatically.
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s === 'AWAITING_RECIPIENT' || s === 'OUTGOING_SENT' ? 5000 : false;
    },
  });

  function refresh() {
    qc.invalidateQueries({ queryKey: ['handoff', handoffId] });
    qc.invalidateQueries({ queryKey: ['dashboard'] });
    qc.invalidateQueries({ queryKey: ['handoffs'] });
  }

  const action = useMutation({
    mutationFn: (fn: () => Promise<HandoffDetail>) => fn(),
    onSuccess: () => { setActionError(null); refresh(); },
    // Sending a draft needs an active subscription: that refusal is the subscription dialog, not a technical error.
    onError: (err) => {
      if (isSubscriptionEnded(err)) void showSubscriptionEnded();
      else setActionError(errorMessage(err));
    },
  });

  const recordReturn = useMutation({
    mutationFn: (input: CreateReturnInput) => returnApi.create(handoffId, input),
    onSuccess: () => { setActionError(null); setShowReturn(false); setPrefill(null); refresh(); },
    onError: (err) => setActionError(errorMessage(err)),
  });

  const confirmReturn = useMutation({
    mutationFn: (returnId: number) => returnApi.confirm(handoffId, returnId),
    onSuccess: () => { setActionError(null); refresh(); },
    onError: (err) => setActionError(errorMessage(err)),
  });

  if (detail.isLoading) return <Spinner />;
  if (detail.isError) return <ErrorNotice error={detail.error} />;
  const h = detail.data!;
  const has = (a: HandoffAction) => h.availableActions.includes(a);

  async function withPdfBusy(kind: 'download' | 'share' | 'email', work: () => Promise<void>) {
    setActionError(null);
    setNotice(null);
    setPdfBusy(kind);
    try {
      await work();
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setPdfBusy(null);
    }
  }

  const onDownloadPdf = () => withPdfBusy('download', async () => {
    const { blob, filename } = await handoffApi.downloadPdf(handoffId);
    saveBlob(blob, filename);
  });

  // Shares the PDF file itself through the device's native share sheet. Which apps appear is up to the
  // OS/browser; where file sharing isn't supported the PDF is downloaded instead.
  const onSharePdf = () => withPdfBusy('share', async () => {
    let file = sharePdf.current?.version === h.updatedAt ? sharePdf.current.file : null;
    if (!file) {
      const { blob, filename } = await handoffApi.downloadPdf(handoffId);
      file = new File([blob], filename, { type: 'application/pdf' });
      sharePdf.current = { version: h.updatedAt, file };
    }
    const data: ShareData = {
      files: [file], title: `Proof of Handoff — ${h.publicCode}`, text: `Proof-of-Handoff record for ${h.publicCode}.`,
    };
    if (typeof navigator.canShare !== 'function' || !navigator.canShare(data)) {
      saveBlob(file, file.name);
      setNotice({ text: "Sharing files isn't supported in this browser, so the PDF was downloaded instead.", tone: 'notice-info' });
      return;
    }
    try {
      await navigator.share(data);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;   // the user closed the share sheet
      if (err instanceof DOMException && err.name === 'NotAllowedError') {     // the gesture expired while the PDF was prepared
        setNotice({ text: 'The PDF is ready — press Share again to open the share sheet.', tone: 'notice-info' });
        return;
      }
      throw err;
    }
  });

  async function onEmailPdf() {
    const res = await confirm({
      title: 'Email Proof-of-Handoff PDF',
      message: `The PDF for ${h.publicCode} will be attached to an email. Sending is only ever done by you, here.`,
      confirmText: 'Send email',
      input: { label: 'Send to', placeholder: 'name@example.com', required: true, defaultValue: h.recipientEmail },
    });
    if (!res.confirmed) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(res.value)) {
      setActionError('Enter a valid email address.');
      return;
    }
    await withPdfBusy('email', async () => {
      const sent = await handoffApi.emailPdf(handoffId, res.value);
      setNotice(sent.delivered
        ? { text: `Email sent successfully for ${h.publicCode} to ${sent.sentTo}.`, tone: 'notice-success' }
        // Development mode: the server only logged the message. Say so rather than claim an email.
        : { text: `Email isn't set up on this server, so nothing was sent for ${h.publicCode} to ${sent.sentTo}. The message was only logged.`, tone: 'notice-info' });
    });
  }

  function closeReturnForm() {
    setShowReturn(false);
    setPrefill(null);
  }

  async function onRecordReturn() {
    if (showReturn) { closeReturnForm(); return; }
    const res = await confirm({
      title: 'How do you want to enter the returned quantities?',
      message: <span className="muted small">Use Import from File when there are many items.</span>,
      choices: [
        { value: 'manual', label: 'Enter Manually', primary: true },
        { value: 'import', label: 'Import from File' },
      ],
    });
    if (!res.confirmed) return;
    if (res.value === 'import') navigate(`/handoff-check?returnFor=${handoffId}`);
    else setShowReturn(true);
  }

  async function onDelete() {
    const res = await confirm({
      title: 'Delete draft handoff?',
      message: 'This cannot be undone.',
      danger: true, confirmText: 'Delete',
    });
    if (!res.confirmed) return;
    try {
      await handoffApi.remove(handoffId);
      refresh();
      navigate('/dashboard');
    } catch (err) {
      setActionError(errorMessage(err));
    }
  }

  async function onCancel() {
    const res = await confirm({
      title: 'Cancel this handoff?',
      message: 'The recipient will no longer be able to act on it.',
      danger: true, confirmText: 'Cancel handoff', cancelText: 'Keep',
      input: { label: 'Reason (optional)', placeholder: 'Why are you cancelling?' },
    });
    if (res.confirmed) action.mutate(() => handoffApi.cancel(handoffId, res.value || undefined));
  }

  async function onDispute() {
    const res = await confirm({
      title: 'Mark as disputed?',
      input: { label: 'Reason (optional)', placeholder: 'Describe the dispute' },
      confirmText: 'Mark disputed',
    });
    if (res.confirmed) action.mutate(() => handoffApi.dispute(handoffId, res.value || undefined));
  }

  async function onClose() {
    // Everything returned → close directly. Items left missing → close is allowed, but
    // the owner must give a reason for finalising with items unreturned.
    if (Number(h.totalMissing) <= 0) {
      action.mutate(() => handoffApi.close(handoffId));
      return;
    }
    const res = await confirm({
      title: 'Close with missing items?',
      message: <>This handoff has <strong>{qty(h.totalMissing)}</strong> item(s) recorded as missing. Closing finalises it with those items unreturned.</>,
      confirmText: 'Close handoff',
      input: { label: 'Reason (required)', placeholder: 'e.g. Items written off as lost', required: true },
    });
    if (res.confirmed) action.mutate(() => handoffApi.close(handoffId, res.value));
  }

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <Link to="/dashboard" className="small muted">← Dashboard</Link>
          <h1 style={{ marginTop: 4 }}>{h.title}</h1>
          <div className="row">
            <StatusBadge status={h.status} />
            {h.overdue && <span className="badge badge-danger">Overdue</span>}
            <span className="muted small">{h.publicCode}</span>
            {h.category && <span className="badge">{h.category}</span>}
          </div>
        </div>
      </div>

      {actionError && <div className="notice notice-error">{actionError}</div>}
      {/* Floating, so a result is seen wherever the page is scrolled; the sending state is shown while it works. */}
      {(notice || pdfBusy === 'email') && (
        <div className="toast-area" role="status" aria-live="polite">
          {pdfBusy === 'email' && <div className="notice notice-info toast">Sending email… this can take a few seconds.</div>}
          {notice && <div className={`notice ${notice.tone} toast`}>{notice.text}</div>}
        </div>
      )}

      {/* Actions */}
      <div className="card">
        <div className="row">
          {has('EDIT') && <Link className="btn" to={`/handoffs/${handoffId}/edit`}>Edit draft</Link>}
          {has('SUBMIT') && <button className="btn btn-primary" disabled={action.isPending}
            onClick={() => action.mutate(() => handoffApi.submit(handoffId))}>Submit & send link</button>}
          {has('RESEND_LINK') && <button className="btn" disabled={action.isPending}
            onClick={() => action.mutate(() => handoffApi.resendLink(handoffId))}>Resend link</button>}
          {has('RECORD_RETURN') && <button className="btn btn-primary" onClick={onRecordReturn}>
            {showReturn ? 'Close return form' : 'Record return'}</button>}
          {has('CLOSE') && <button className="btn btn-danger" disabled={action.isPending}
            onClick={onClose}>Close handoff</button>}
          {has('REQUEST_MISSING_CONFIRMATION') && <button className="btn btn-primary" disabled={action.isPending}
            onClick={() => action.mutate(() => handoffApi.requestMissingConfirmation(handoffId))}>
            {h.missingConfirmationRequestedAt ? 'Resend missing-confirmation link' : 'Request missing confirmation'}</button>}
          {has('DISPUTE') && <button className="btn" disabled={action.isPending}
            onClick={onDispute}>Mark disputed</button>}
          {has('CANCEL') && <button className="btn btn-danger" disabled={action.isPending}
            onClick={onCancel}>Cancel</button>}
          {has('DELETE') && <button className="btn btn-danger" onClick={onDelete}>Delete draft</button>}
          {/* Any handoff can be the starting point of a new one; it opens the ordinary New handoff form, prefilled. */}
          <Link className="btn" to={`/handoffs/new?from=${handoffId}`}
                title="Start a new draft from this handoff's items and details">Duplicate</Link>
        </div>
        {h.availableActions.length === 0 && <p className="muted small" style={{ margin: 0 }}>This handoff is closed and read-only.</p>}
        {h.status !== 'DRAFT' && (
          <div className="row" style={{ marginTop: '0.75rem', paddingTop: '0.75rem', borderTop: '1px solid var(--border)' }}>
            <span className="muted small">Proof of Handoff</span>
            <button className={`btn ${h.status === 'CLOSED' ? 'btn-primary' : ''}`} disabled={pdfBusy !== null}
                    onClick={onDownloadPdf}>{pdfBusy === 'download' ? 'Generating PDF…' : 'Download PDF'}</button>
            <button className="btn" disabled={pdfBusy !== null} onClick={onSharePdf}>
              {pdfBusy === 'share' ? 'Preparing PDF…' : 'Share'}</button>
            <button className="btn" disabled={pdfBusy !== null} onClick={onEmailPdf}>
              {pdfBusy === 'email' ? 'Sending…' : 'Email PDF'}</button>
          </div>
        )}
      </div>

      {/* Record return form */}
      {showReturn && has('RECORD_RETURN') && (
        <div className="card">
          <h2>Record a return</h2>
          <ReturnForm items={h.items} prefill={prefill} busy={recordReturn.isPending}
                      onSubmit={(input) => recordReturn.mutate(input)}
                      onCancel={closeReturnForm} />
        </div>
      )}

      {/* Missing-items status. Once finished only the settled outcome is shown (the loss the recipient confirmed);
          the "waiting for / request confirmation" wording and the wait-for-return banner below are for open work only. */}
      {Number(h.totalMissing) > 0 && (!isFinished(h.status) || h.missingConfirmedAt) && (
        <div className={`notice ${h.missingConfirmedAt ? (isFinished(h.status) ? 'notice-error' : 'notice-success') : 'notice-info'}`}>
          {h.missingConfirmedAt
            ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — confirmed by {h.missingConfirmedByName ? <strong>{h.missingConfirmedByName}</strong> : 'the recipient'} on {formatDateTime(h.missingConfirmedAt)}.{!isFinished(h.status) && ' You can now close this handoff.'}</>
            : h.returnWaitRequestedAt
              ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — the recipient requested to wait for return. You can re-send the confirmation request.</>
              : h.missingConfirmationRequestedAt
                ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — waiting for the recipient to confirm before you can close. (Confirmation link sent.)</>
                : <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing. Request the recipient to confirm the missing items before closing this handoff.</>}
        </div>
      )}
      {/* Recipient's return-wait request detail */}
      {!isFinished(h.status) && h.returnWaitRequestedAt && !h.missingConfirmedAt && (
        <div className="notice notice-warning">
          <strong>{h.returnWaitRequestedByName || 'The recipient'}</strong> requested to wait for return on {formatDateTime(h.returnWaitRequestedAt)}:
          <blockquote style={{ margin: '0.4rem 0 0', fontStyle: 'italic' }}>&ldquo;{h.returnWaitReason}&rdquo;</blockquote>
        </div>
      )}

      {/* Totals + items */}
      <div className="card">
        <div className="card-header">
          <h2>Items</h2>
          <div className="row small">
            <span className="muted">Outgoing <strong>{qty(h.totalOutgoing)}</strong></span>
            <span className="muted">Returned <strong>{qty(h.totalReturned)}</strong></span>
            {Number(h.totalMissing) > 0 && <span className="badge badge-danger">Missing {qty(h.totalMissing)}</span>}
            <span className={Number(h.totalRemaining) > 0 ? 'remaining-open' : 'remaining-zero'}>
              Remaining <strong>{qty(h.totalRemaining)}</strong></span>
          </div>
        </div>
        <ItemsTable items={h.items} />
      </div>

      {/* Return history */}
      <div className="card">
        <h2>Return history</h2>
        {h.returns.length === 0 ? <p className="muted">No returns recorded yet.</p> : (
          <div className="stack">
            {h.returns.map((r) => (
              <div key={r.id} className="card" style={{ background: 'var(--surface-2)' }}>
                <div className="spread">
                  <div>
                    <strong>{formatDateTime(r.occurredAt)}</strong>{' '}
                    <span className="muted small">
                      by {r.enteredByType === 'RECIPIENT' ? 'recipient' : r.enteredByRef ?? 'owner'}
                    </span>
                    {r.confirmed
                      ? <span className="badge badge-success" style={{ marginLeft: 6 }}>Confirmed</span>
                      : <span className="badge badge-warning" style={{ marginLeft: 6 }}>Pending confirmation</span>}
                  </div>
                  {!r.confirmed && has('CONFIRM_RETURN') && (
                    <button className="btn btn-sm btn-primary" disabled={confirmReturn.isPending}
                            onClick={() => confirmReturn.mutate(r.id)}>Confirm</button>
                  )}
                </div>
                {r.note && <p className="small" style={{ margin: '0.4rem 0' }}>{r.note}</p>}
                <ul className="small" style={{ margin: '0.4rem 0 0', paddingLeft: '1.1rem' }}>
                  {r.lines.map((l) => (
                    <li key={l.id}>
                      {l.itemName}: <strong>{qty(l.quantity)}</strong>
                      {' '}<span className="muted">({CONDITION_LABELS[l.condition] ?? l.condition})</span>
                      {l.note && <span className="muted"> — {l.note}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Details */}
      <div className="card">
        <h2>Details</h2>
        <dl className="kv">
          <dt>Sender</dt><dd>{h.senderName}{h.senderOrganization ? ` · ${h.senderOrganization}` : ''}</dd>
          <dt>Recipient</dt><dd>{h.recipientName} · {h.recipientEmail}{h.recipientPhone ? ` · ${h.recipientPhone}` : ''}</dd>
          {h.purpose && <><dt>Purpose</dt><dd>{h.purpose}</dd></>}
          <dt>Created</dt><dd>{formatDateTime(h.createdAt)}</dd>
          <dt>Outgoing sent</dt><dd>{formatDateTime(h.outgoingAt)}</dd>
          <dt>Accepted</dt><dd>{formatDateTime(h.acceptanceAt)}</dd>
          <dt>Due / expected</dt><dd>{formatDateTime(h.dueAt)}</dd>
          {h.acknowledgementName && (
            <><dt>Acknowledged by</dt>
              <dd>{h.acknowledgementName} <span className="muted small">(typed acknowledgement — not a legally binding e-signature)</span></dd></>
          )}
          {h.rejectionReason && <><dt>Rejection reason</dt><dd>{h.rejectionReason}</dd></>}
        </dl>
      </div>

      {/* Attachments */}
      <AttachmentsPanel handoffId={handoffId} attachments={h.attachments}
                        canModify={has('ADD_ATTACHMENT')} onChanged={refresh} />

      {/* Event history */}
      <div className="card">
        <h2>Event history</h2>
        <ul className="timeline">
          {h.events.map((e) => (
            <li key={e.id}>
              <div className="small"><strong>{e.message}</strong></div>
              <div className="small muted">
                {formatDateTime(e.at)} · {e.actorType.toLowerCase()}{e.actorRef ? ` · ${e.actorRef}` : ''}
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
