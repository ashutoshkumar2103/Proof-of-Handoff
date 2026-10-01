import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import { ErrorNotice, Spinner, errorMessage } from '../components/ui';
import { isoToLocalParts } from '../lib/format';

export function EditHandoffPage() {
  const { id } = useParams();
  const handoffId = Number(id);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const detail = useQuery({ queryKey: ['handoff', handoffId], queryFn: () => handoffApi.get(handoffId) });

  if (detail.isLoading) return <Spinner />;
  if (detail.isError) return <ErrorNotice error={detail.error} />;
  const h = detail.data!;

  if (h.status !== 'DRAFT') {
    return (
      <div className="card">
        <p className="muted">Only draft handoffs can be edited.</p>
        <button className="btn" onClick={() => navigate(`/handoffs/${handoffId}`)}>Back to handoff</button>
      </div>
    );
  }

  const initial = emptyFormValue({
    title: h.title,
    category: h.category ?? '',
    purpose: h.purpose ?? '',
    senderName: h.senderName,
    senderOrganization: h.senderOrganization ?? '',
    recipientName: h.recipientName,
    recipientEmail: h.recipientEmail,
    recipientPhone: h.recipientPhone ?? '',
    dueDate: isoToLocalParts(h.dueAt).date,
    dueTime: isoToLocalParts(h.dueAt).time,
    items: h.items.length > 0
      ? h.items.map((it) => ({
          name: it.name, quantity: it.outgoing, unit: it.unit ?? '',
          condition: it.condition ?? 'GOOD', serialNumber: it.serialNumber ?? '', notes: it.notes ?? '',
        }))
      : emptyFormValue().items,
  });

  return (
    <div className="stack page-narrow">
      <h1>Edit draft</h1>
      <HandoffForm
        initial={initial}
        submitLabel="Save changes"
        busy={busy}
        error={error}
        onSubmit={async ({ header, items }) => {
          setError(null);
          setBusy(true);
          try {
            await handoffApi.update(handoffId, header);
            await handoffApi.replaceItems(handoffId, items);
            await qc.invalidateQueries({ queryKey: ['handoff', handoffId] });
            await qc.invalidateQueries({ queryKey: ['handoffs'] });
            navigate(`/handoffs/${handoffId}`);
          } catch (err) {
            setError(errorMessage(err));
            setBusy(false);
          }
        }}
      />
    </div>
  );
}
