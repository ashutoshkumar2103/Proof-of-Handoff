import { useRef, useState } from 'react';
import { downloadBlob, saveBlob } from '../api/client';
import { attachmentApi } from '../api/endpoints';
import type { Attachment, AttachmentKind } from '../api/types';
import { errorMessage } from './ui';
import { useConfirm } from './ConfirmDialog';
import { formatBytes } from '../lib/format';

export function AttachmentsPanel({ handoffId, attachments, canModify, onChanged }: {
  handoffId: number;
  attachments: Attachment[];
  canModify: boolean;
  onChanged: () => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [kind, setKind] = useState<AttachmentKind>('EVIDENCE');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();

  async function onUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setBusy(true);
    try {
      await attachmentApi.upload(handoffId, file, kind);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function onDownload(a: Attachment) {
    try {
      saveBlob(await downloadBlob(`/handoffs/${handoffId}/attachments/${a.id}/content`), a.originalFilename);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function onDelete(a: Attachment) {
    const res = await confirm({
      title: 'Remove attachment?',
      message: `"${a.originalFilename}" will be removed from this handoff.`,
      danger: true, confirmText: 'Remove',
    });
    if (!res.confirmed) return;
    setError(null);
    try {
      await attachmentApi.remove(handoffId, a.id);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="card">
      <div className="card-header">
        <h2>Attachments & documents</h2>
        {canModify && (
          <div className="row">
            <select value={kind} onChange={(e) => setKind(e.target.value as AttachmentKind)} style={{ width: 'auto' }}>
              <option value="EVIDENCE">Evidence</option>
              <option value="REFERENCE_DOCUMENT">Reference document</option>
            </select>
            <button className="btn btn-sm" disabled={busy} onClick={() => fileRef.current?.click()}>
              {busy ? 'Uploading…' : 'Upload'}
            </button>
            <input ref={fileRef} type="file" className="hidden" onChange={onUpload} />
          </div>
        )}
      </div>
      {error && <div className="notice notice-error mb-2">{error}</div>}
      {attachments.length === 0 ? (
        <p className="muted">No attachments yet.</p>
      ) : (
        <div className="stack">
          {attachments.map((a) => (
            <div key={a.id} className="spread" style={{ padding: '0.4rem 0', borderBottom: '1px solid var(--border)' }}>
              <div>
                <button className="btn-ghost btn btn-sm" onClick={() => onDownload(a)}>{a.originalFilename}</button>
                <span className={`badge ${a.kind === 'REFERENCE_DOCUMENT' ? 'badge-primary' : ''}`} style={{ marginLeft: 6 }}>
                  {a.kind === 'REFERENCE_DOCUMENT' ? 'Reference' : 'Evidence'}
                </span>
              </div>
              <div className="row small muted">
                <span>{formatBytes(a.sizeBytes)}</span>
                {canModify && <button className="btn btn-sm btn-ghost" onClick={() => onDelete(a)}>Delete</button>}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
