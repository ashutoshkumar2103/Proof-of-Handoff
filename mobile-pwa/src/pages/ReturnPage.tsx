import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { handoffApi, returnApi } from '../api/endpoints';
import type { HandoffItem, ItemCondition, ReturnLineInput } from '../api/types';
import { errorMessage, ErrorNotice, Spinner } from '../components/ui';
import { qty } from '../lib/format';
import { owed, remaining, returnFigures } from '../lib/quantity';
import type { ReturnRow } from '../lib/quantity';

type ReturnedCondition = 'GOOD' | 'DAMAGED' | 'OTHER';
const RETURNED_CONDITIONS: { value: ReturnedCondition; label: string }[] = [
  { value: 'GOOD', label: 'Good' }, { value: 'DAMAGED', label: 'Damaged' }, { value: 'OTHER', label: 'Other' },
];

interface RowState extends ReturnRow {
  include: boolean;
  condition: ReturnedCondition;
  note: string;
}

/** Recording a return by hand. The backend works out and enforces every figure; the screen only shows the outcome as it is typed. */
export function ReturnPage() {
  const id = Number(useParams().id);
  const detail = useQuery({ queryKey: ['handoff', id], queryFn: () => handoffApi.get(id) });

  if (detail.isLoading) return <Spinner />;
  if (detail.isError) return <ErrorNotice error={detail.error} onRetry={() => void detail.refetch()} />;
  const h = detail.data!;
  if (!h.availableActions.includes('RECORD_RETURN')) return <Navigate to={`/h/${id}`} replace />;
  return (
    <div className="stack">
      <Link to={`/h/${id}`} className="back">← {h.publicCode}</Link>
      <h1>Record return</h1>
      <p className="muted small">{h.title} · to {h.recipientName}</p>
      <ReturnForm handoffId={id} items={h.items} />
    </div>
  );
}

function ReturnForm({ handoffId, items }: { handoffId: number; items: HandoffItem[] }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const returnable = items.filter((i) => owed(i) > 0);
  const [rows, setRows] = useState<Record<number, RowState>>(() =>
    Object.fromEntries(returnable.map((i) => [i.id, { include: false, returnQty: remaining(i) > 0 ? i.remaining : '0', missingQty: '0', condition: 'GOOD' as ReturnedCondition, note: '' }])));
  const [error, setError] = useState<string | null>(null);

  const patch = (itemId: number, p: Partial<RowState>) => setRows((r) => ({ ...r, [itemId]: { ...r[itemId], ...p } }));

  const record = useMutation({
    mutationFn: (lines: ReturnLineInput[]) => returnApi.create(handoffId, { lines }),
    onSuccess: async () => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['handoff', handoffId] }),
        qc.invalidateQueries({ queryKey: ['handoffs'] }),
        qc.invalidateQueries({ queryKey: ['dashboard'] }),
      ]);
      navigate(`/h/${handoffId}`, { replace: true, state: { notice: 'Return recorded.' } });
    },
    onError: (err) => setError(errorMessage(err)),
  });

  const included = returnable.filter((i) => rows[i.id]?.include);
  const invalid = included.some((i) => returnFigures(i, rows[i.id]).problem !== null);
  const nothingToRecord = included.length === 0;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const lines: ReturnLineInput[] = [];
    for (const i of included) {
      const row = rows[i.id];
      const { problem } = returnFigures(i, row);
      if (problem) { setError(`“${i.name}”: ${problem}`); return; }
      const rq = Number(row.returnQty) || 0;
      const mq = Number(row.missingQty) || 0;
      if (rq <= 0 && mq <= 0) { setError(`Enter a return or missing quantity for “${i.name}”.`); return; }
      // The return line first, then the missing line: the backend judges the lines in order, and Missing is what would still be outstanding after the return.
      if (rq > 0) lines.push({ itemId: i.id, quantity: row.returnQty, condition: row.condition as ItemCondition, note: row.note.trim() || undefined });
      if (mq > 0) lines.push({ itemId: i.id, quantity: row.missingQty, condition: 'MISSING', note: row.note.trim() || undefined });
    }
    if (lines.length === 0) { setError('Tick at least one item to record.'); return; }
    record.mutate(lines);
  }

  if (returnable.length === 0) return <div className="notice notice-info">Everything has been returned — nothing is outstanding.</div>;

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <p className="muted small">
        Tick each item that is coming back. <strong>Return</strong> is how many are back now; <strong>Missing</strong> marks units as lost.
        Whatever you record neither way stays with the recipient — it is never marked missing for you.
      </p>
      {error && <div className="notice notice-error" role="alert">{error}</div>}

      <ul className="stack">
        {returnable.map((i) => {
          const row = rows[i.id];
          const { still, problem } = returnFigures(i, row);
          const shownProblem = row.include ? problem : null;
          return (
            <li key={i.id} className={`card return-card${row.include ? ' included' : ''}`}>
              <label className="check">
                <input type="checkbox" checked={row.include} onChange={(e) => patch(i.id, { include: e.target.checked })} />
                <span><strong>{i.name}</strong>{i.unit ? <span className="muted"> · {i.unit}</span> : null}</span>
              </label>
              <dl className="figures">
                <div><dt>Given</dt><dd>{qty(i.outgoing)}</dd></div>
                <div><dt>Returned</dt><dd>{qty(i.returnedConfirmed)}</dd></div>
                <div><dt>Missing</dt><dd className={Number(i.missing) > 0 ? 'neg' : ''}>{qty(i.missing)}</dd></div>
                <div><dt>Remaining</dt><dd className="open">{qty(i.remaining)}</dd></div>
              </dl>
              {row.include && (
                <div className="return-fields">
                  <div className="two">
                    <div className="field">
                      <label htmlFor={`rq-${i.id}`}>Return qty</label>
                      <input id={`rq-${i.id}`} type="number" inputMode="decimal" min="0" step="0.001" value={row.returnQty}
                             aria-invalid={!!shownProblem} onChange={(e) => patch(i.id, { returnQty: e.target.value })} />
                    </div>
                    <div className="field">
                      <label htmlFor={`mq-${i.id}`}>Missing</label>
                      <input id={`mq-${i.id}`} type="number" inputMode="decimal" min="0" step="0.001" value={row.missingQty}
                             disabled={remaining(i) <= 0} aria-invalid={!!shownProblem} onChange={(e) => patch(i.id, { missingQty: e.target.value })} />
                    </div>
                  </div>
                  <div className="field">
                    <label htmlFor={`cond-${i.id}`}>Condition of what is returned</label>
                    <select id={`cond-${i.id}`} value={row.condition} onChange={(e) => patch(i.id, { condition: e.target.value as ReturnedCondition })}>
                      {RETURNED_CONDITIONS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label htmlFor={`note-${i.id}`}>Note <span className="muted">(optional)</span></label>
                    <input id={`note-${i.id}`} value={row.note} onChange={(e) => patch(i.id, { note: e.target.value })} />
                  </div>
                  <p className="still" aria-live="polite">
                    Still with recipient: {shownProblem ? <span className="muted">—</span> : <strong>{qty(still)}</strong>}
                  </p>
                  {shownProblem && <div className="form-error" role="alert">{shownProblem}</div>}
                </div>
              )}
            </li>
          );
        })}
      </ul>

      <div className="action-bar">
        <Link to={`/h/${handoffId}`} className="btn btn-negative">Cancel</Link>
        <button className="btn btn-primary" disabled={record.isPending || nothingToRecord || invalid}>
          {record.isPending ? 'Recording…' : 'Record return'}
        </button>
      </div>
    </form>
  );
}
