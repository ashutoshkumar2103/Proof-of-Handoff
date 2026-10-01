import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import { errorMessage } from '../components/ui';

export function CreateHandoffPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const initial = emptyFormValue({
    senderName: user?.displayName ?? '',
    senderOrganization: user?.organization ?? '',
  });

  return (
    <div className="stack page-narrow">
      <h1>New handoff</h1>
      <p className="muted">Create a draft, add the items, then submit to email the recipient a secure link.</p>
      <HandoffForm
        initial={initial}
        submitLabel="Create draft"
        busy={busy}
        error={error}
        onSubmit={async ({ header, items }) => {
          setError(null);
          setBusy(true);
          try {
            const created = await handoffApi.create({ ...header, items });
            await qc.invalidateQueries({ queryKey: ['dashboard'] });
            await qc.invalidateQueries({ queryKey: ['handoffs'] });
            navigate(`/handoffs/${created.id}`);
          } catch (err) {
            setError(errorMessage(err));
            setBusy(false);
          }
        }}
      />
    </div>
  );
}
