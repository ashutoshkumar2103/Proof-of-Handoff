import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isSubscriptionRefusal } from '../api/client';
import { handoffApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import type { HandoffFormSubmit, SubmitMode } from '../components/HandoffForm';
import { errorMessage, Spinner } from '../components/ui';
import { NO_ACTIVE_SUBSCRIPTION_MESSAGE, SUBSCRIPTION_ENDED_MESSAGE } from '../lib/format';

/** Creating a handoff: a blank form, or one started from an earlier handoff (`?from=<id>`, which is what Duplicate opens). */
export function NewHandoffPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fromId = Number(useSearchParams()[0].get('from'));
  const template = useQuery({
    queryKey: ['handoff-template', fromId],
    queryFn: () => handoffApi.template(fromId),
    enabled: fromId > 0,
    retry: false,
    staleTime: 0,
  });

  // Whether the plan allows this is the backend's call; this only says so early. Every request is judged again there.
  const noPlan = user?.subscription.status === 'INACTIVE';
  const refusal = noPlan ? (user?.subscription.plan ? SUBSCRIPTION_ENDED_MESSAGE : NO_ACTIVE_SUBSCRIPTION_MESSAGE) : null;

  if (fromId > 0 && template.isLoading) return <Spinner />;
  const t = template.data;

  const initial = emptyFormValue({
    senderName: t?.senderName ?? user?.displayName ?? '',
    senderOrganization: t?.senderOrganization ?? user?.organization ?? '',
    ...(t ? {
      title: t.title,
      category: t.category ?? '',
      purpose: t.purpose ?? '',
      ...(t.items.length > 0 ? {
        items: t.items.map((i) => ({ name: i.name, quantity: String(i.quantity), unit: i.unit ?? '', condition: 'GOOD' as const, serialNumber: '', notes: '' })),
      } : {}),
    } : {}),
  });

  async function onSubmit({ header, items }: HandoffFormSubmit, mode: SubmitMode) {
    setError(null);
    setBusy(true);
    let created;
    try {
      created = await handoffApi.create({ ...header, items });
    } catch (err) {
      setBusy(false);
      setError(errorMessage(err));
      return;
    }
    await Promise.all([qc.invalidateQueries({ queryKey: ['dashboard'] }), qc.invalidateQueries({ queryKey: ['handoffs'] })]);
    if (mode === 'send') {
      try {
        await handoffApi.submit(created.id);
        navigate(`/h/${created.id}`, { replace: true, state: { notice: 'Saved, and the link was sent to the recipient.' } });
      } catch (err) {
        // The draft exists: say why it was not sent, and let the person try again from the handoff itself.
        navigate(`/h/${created.id}`, {
          replace: true,
          state: { problem: `Your draft was saved, but it wasn’t sent: ${isSubscriptionRefusal(err) ? 'your plan doesn’t allow sending right now. ' : ''}${errorMessage(err)}` },
        });
      }
      return;
    }
    navigate(`/h/${created.id}`, { replace: true, state: { notice: 'Draft saved.' } });
  }

  return (
    <div className="stack">
      <h1>New handoff</h1>
      {fromId > 0 && (t ? (
        <div className="notice notice-info">
          Started from an earlier handoff: its title and items are filled in. Add who it is for and check everything; nothing is
          saved until you save the draft.
        </div>
      ) : (
        <div className="notice notice-warning">
          That handoff couldn’t be used as a starting point, so this is a blank form. <Link to="/">Back to handoffs</Link>
        </div>
      ))}
      {refusal && <div className="notice notice-warning" role="alert">{refusal}</div>}
      <fieldset className="plain" disabled={!!refusal}>
        <HandoffForm key={t ? `from-${fromId}` : 'blank'} initial={initial} busy={busy} error={error} canSend onSubmit={onSubmit} />
      </fieldset>
    </div>
  );
}
