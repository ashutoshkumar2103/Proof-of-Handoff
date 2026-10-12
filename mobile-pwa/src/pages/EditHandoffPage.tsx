import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import { errorMessage, ErrorNotice, Spinner } from '../components/ui';
import { isoToLocalParts } from '../lib/format';

/** Editing a draft (the backend only offers EDIT while it is one): the same form, saved as the details and the items. */
export function EditHandoffPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const detail = useQuery({ queryKey: ['handoff', id], queryFn: () => handoffApi.get(id) });

  if (detail.isLoading) return <Spinner />;
  if (detail.isError) return <ErrorNotice error={detail.error} onRetry={() => void detail.refetch()} />;
  const h = detail.data!;
  if (!h.availableActions.includes('EDIT')) return <Navigate to={`/h/${id}`} replace />;

  const due = isoToLocalParts(h.dueAt);
  const initial = emptyFormValue({
    title: h.title, category: h.category ?? '', purpose: h.purpose ?? '',
    senderName: h.senderName, senderOrganization: h.senderOrganization ?? '',
    recipientName: h.recipientName, recipientEmail: h.recipientEmail, recipientPhone: h.recipientPhone ?? '',
    dueDate: due.date, dueTime: due.time,
    items: h.items.map((i) => ({
      name: i.name, quantity: String(Number(i.outgoing)), unit: i.unit ?? '', condition: i.condition ?? 'GOOD',
      serialNumber: i.serialNumber ?? '', notes: i.notes ?? '',
    })),
  });

  return (
    <div className="stack">
      <Link to={`/h/${id}`} className="back">← {h.publicCode ?? 'Draft'}</Link>
      <h1>Edit draft</h1>
      <HandoffForm initial={initial} busy={busy} error={error} saveLabel="Save changes"
        onSubmit={async ({ header, items }) => {
          setError(null);
          setBusy(true);
          try {
            await handoffApi.update(id, header);
            await handoffApi.replaceItems(id, items);
            await Promise.all([
              qc.invalidateQueries({ queryKey: ['handoff', id] }),
              qc.invalidateQueries({ queryKey: ['handoffs'] }),
              qc.invalidateQueries({ queryKey: ['dashboard'] }),
            ]);
            navigate(`/h/${id}`, { replace: true, state: { notice: 'Changes saved.' } });
          } catch (err) {
            setBusy(false);
            setError(errorMessage(err));
          }
        }} />
    </div>
  );
}
