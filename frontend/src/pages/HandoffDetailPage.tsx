import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi, returnApi } from '../api/endpoints';
import type { CreateReturnInput, HandoffAction, HandoffDetail } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { ItemsTable } from '../components/ItemsTable';
import { ReturnForm } from '../components/ReturnForm';
import { AttachmentsPanel } from '../components/AttachmentsPanel';
import { ErrorNotice, Spinner, errorMessage } from '../components/ui';
import { useConfirm } from '../components/ConfirmDialog';
import { CONDITION_LABELS, formatDateTime, qty } from '../lib/format';

export function HandoffDetailPage() {
  const { id } = useParams();
  const handoffId = Number(id);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [showReturn, setShowReturn] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const confirm = useConfirm();

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
    onError: (err) => setActionError(errorMessage(err)),
  });

  const recordReturn = useMutation({
    mutationFn: (input: CreateReturnInput) => returnApi.create(handoffId, input),
    onSuccess: () => { setActionError(null); setShowReturn(false); refresh(); },
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

      {/* Actions */}
      <div className="card">
        <div className="row">
          {has('EDIT') && <Link className="btn" to={`/handoffs/${handoffId}/edit`}>Edit draft</Link>}
          {has('SUBMIT') && <button className="btn btn-primary" disabled={action.isPending}
            onClick={() => action.mutate(() => handoffApi.submit(handoffId))}>Submit & send link</button>}
          {has('RESEND_LINK') && <button className="btn" disabled={action.isPending}
            onClick={() => action.mutate(() => handoffApi.resendLink(handoffId))}>Resend link</button>}
          {has('RECORD_RETURN') && <button className="btn btn-primary" onClick={() => setShowReturn((s) => !s)}>
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
        </div>
        {h.availableActions.length === 0 && <p className="muted small" style={{ margin: 0 }}>This handoff is closed and read-only.</p>}
      </div>

      {/* Record return form */}
      {showReturn && has('RECORD_RETURN') && (
        <div className="card">
          <h2>Record a return</h2>
          <ReturnForm items={h.items} busy={recordReturn.isPending}
                      onSubmit={(input) => recordReturn.mutate(input)}
                      onCancel={() => setShowReturn(false)} />
        </div>
      )}

      {/* Missing-items confirmation status */}
      {Number(h.totalMissing) > 0 && (
        <div className={`notice ${h.missingConfirmedAt ? (h.status === 'CLOSED' ? 'notice-error' : 'notice-success') : 'notice-info'}`}>
          {h.missingConfirmedAt
            ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — confirmed by {h.missingConfirmedByName ? <strong>{h.missingConfirmedByName}</strong> : 'the recipient'} on {formatDateTime(h.missingConfirmedAt)}.{h.status !== 'CLOSED' && ' You can now close this handoff.'}</>
            : h.returnWaitRequestedAt
              ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — the recipient requested to wait for return. You can re-send the confirmation request.</>
              : h.missingConfirmationRequestedAt
                ? <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing — waiting for the recipient to confirm before you can close. (Confirmation link sent.)</>
                : <><strong>{qty(h.totalMissing)}</strong> item(s) reported missing. Request the recipient to confirm the missing items before closing this handoff.</>}
        </div>
      )}
      {/* Recipient's return-wait request detail */}
      {h.returnWaitRequestedAt && !h.missingConfirmedAt && (
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
