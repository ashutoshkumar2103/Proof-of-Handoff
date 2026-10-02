import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import { isSubscriptionEnded } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useSubscriptionGate } from '../auth/useSubscriptionGate';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import { errorMessage, Gated, Spinner } from '../components/ui';

export function CreateHandoffPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Starting a handoff needs an active subscription: the gate checks it while the page is open and shows the dialog.
  const gate = useSubscriptionGate();
  const { handle } = gate;

  // "Duplicate" opens this same page with ?from=<id>: the form is prefilled from what the server calls reusable.
  const fromId = Number(useSearchParams()[0].get('from'));
  const template = useQuery({
    queryKey: ['handoff-template', fromId],
    queryFn: () => handoffApi.template(fromId),
    enabled: fromId > 0 && gate.state === 'active',
    retry: false,
    staleTime: 0,
  });
  const templateRefused = isSubscriptionEnded(template.error);
  useEffect(() => { handle(template.error); }, [template.error, handle]);   // the subscription ended since the check

  if (gate.state === 'checking' || (fromId > 0 && gate.state === 'active' && template.isLoading)) return <Spinner />;
  // Never shown as usable without a subscription: all the customer sees is the dialog.
  if (!gate.shown || templateRefused) return null;
  const t = template.data;

  const initial = emptyFormValue({
    senderName: t?.senderName ?? user?.displayName ?? '',
    senderOrganization: t?.senderOrganization ?? user?.organization ?? '',
    ...(t ? {
      title: t.title,
      category: t.category ?? '',
      purpose: t.purpose ?? '',
      // Only when there are items: an empty list must leave the form's own blank first row in place.
      ...(t.items.length > 0 ? {
        items: t.items.map((i) => ({
          name: i.name, quantity: String(i.quantity), unit: i.unit ?? '', condition: 'GOOD' as const, serialNumber: '', notes: '',
        })),
      } : {}),
    } : {}),
  });

  return (
    <div className="stack page-narrow">
      <h1>New handoff</h1>
      <p className="muted">Create a draft, add the items, then submit to email the recipient a secure link.</p>
      {fromId > 0 && (t ? (
        <div className="notice notice-info">
          Started from an earlier handoff: its title, items and quantities are filled in. Add who it is for and check
          everything; nothing is saved until you press <strong>Create draft</strong>. The recipient, dates, files, returns
          and history of the original are not copied.
        </div>
      ) : (
        <div className="notice notice-warning">
          That handoff could not be used as a starting point, so this is a blank form. <Link to="/dashboard">Back to the dashboard</Link>
        </div>
      ))}
      <Gated blocked={gate.blocked}>
        <HandoffForm
          key={t ? `from-${fromId}` : 'blank'}
          initial={initial}
          submitLabel="Create draft"
          busy={busy}
          error={error}
          onSubmit={async ({ header, items }) => {
            if (gate.blocked) return;   // the disabled fieldset already makes this unreachable by hand; this covers anything programmatic
            setError(null);
            setBusy(true);
            try {
              const created = await handoffApi.create({ ...header, items });
              await qc.invalidateQueries({ queryKey: ['dashboard'] });
              await qc.invalidateQueries({ queryKey: ['handoffs'] });
              navigate(`/handoffs/${created.id}`);
            } catch (err) {
              setBusy(false);
              if (!handle(err)) setError(errorMessage(err));
            }
          }}
        />
      </Gated>
    </div>
  );
}
