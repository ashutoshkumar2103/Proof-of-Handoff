import { useEffect, useRef, useState } from 'react';
import type { CreateReturnInput, HandoffItem, ItemCondition, ReturnLineInput, ReturnPrefill } from '../api/types';
import { qty } from '../lib/format';

/** Condition of the units coming back now. */
type ReturnedCondition = 'GOOD' | 'DAMAGED' | 'OTHER';
interface RowState {
  include: boolean;
  returnQty: string;      // units coming back now (clears outstanding first, then recovers missing)
  condition: ReturnedCondition;
  missingQty: string;     // outstanding units newly marked lost
  note: string;
}

const RETURNED_CONDITIONS: ReturnedCondition[] = ['GOOD', 'DAMAGED', 'OTHER'];
const LABEL: Record<ReturnedCondition, string> = { GOOD: 'Good', DAMAGED: 'Damaged', OTHER: 'Other' };

const rem = (i: HandoffItem) => Number(i.remaining) || 0;   // still outstanding
const miss = (i: HandoffItem) => Number(i.missing) || 0;    // currently missing
export const owed = (i: HandoffItem) => rem(i) + miss(i);   // everything not yet returned

/**
 * Records a return with just two quantities per item:
 *  • Return qty (+ Condition) — units coming back now. They clear outstanding units first;
 *    anything beyond that brings previously-missing units back, so returning some of a
 *    missing item leaves the rest missing.
 *  • Missing — mark still-outstanding units as lost.
 * A master checkbox toggles all rows.
 * `prefill` (quantities imported from a file via HandoffCheck) sets only Return qty and ticks
 * those rows; Condition, Missing and notes stay with the user, who can change anything.
 */
export function ReturnForm({ items, prefill, onSubmit, onCancel, busy, error }: {
  items: HandoffItem[];
  prefill?: ReturnPrefill | null;
  onSubmit: (input: CreateReturnInput) => void;
  onCancel?: () => void;
  busy?: boolean;
  error?: string | null;
}) {
  const returnable = items.filter((i) => owed(i) > 0);
  const [rows, setRows] = useState<Record<number, RowState>>(() =>
    Object.fromEntries(returnable.map((i) => {
      const imported = Number(prefill?.[i.id]) > 0 ? prefill![i.id] : null;
      return [i.id, {
        include: imported !== null,
        returnQty: imported ?? (rem(i) > 0 ? i.remaining : '0'),
        condition: 'GOOD' as ReturnedCondition,
        missingQty: '0',
        note: '',
      }];
    })));
  const [note, setNote] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const masterRef = useRef<HTMLInputElement>(null);

  const allIncluded = returnable.length > 0 && returnable.every((i) => rows[i.id]?.include);
  const someIncluded = returnable.some((i) => rows[i.id]?.include);

  useEffect(() => {
    if (masterRef.current) masterRef.current.indeterminate = someIncluded && !allIncluded;
  }, [someIncluded, allIncluded]);

  const patch = (id: number, p: Partial<RowState>) => setRows((r) => ({ ...r, [id]: { ...r[id], ...p } }));

  const setAll = (include: boolean) => setRows((r) => {
    const next = { ...r };
    returnable.forEach((i) => { next[i.id] = { ...next[i.id], include }; });
    return next;
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setLocalError(null);
    const included = returnable.filter((i) => rows[i.id]?.include);
    if (included.length === 0) { setLocalError('Select at least one item.'); return; }

    const lines: ReturnLineInput[] = [];
    for (const i of included) {
      const row = rows[i.id];
      const rq = Number(row.returnQty) || 0;
      const mq = Number(row.missingQty) || 0;
      if (rq <= 0 && mq <= 0) {
        setLocalError(`Enter a return or missing quantity for "${i.name}".`);
        return;
      }
      if (rq > owed(i)) {
        setLocalError(`Return qty for "${i.name}" is more than is still owed (${qty(String(owed(i)))}).`);
        return;
      }
      // Returns clear outstanding first, so the outstanding left to mark missing is rem − min(rq, rem).
      const outstandingLeft = Math.max(0, rem(i) - rq);
      if (mq > outstandingLeft) {
        setLocalError(`"${i.name}": can't mark ${qty(row.missingQty)} missing — only ${qty(String(outstandingLeft))} would be outstanding after this return.`);
        return;
      }
      if (rq > 0) lines.push({ itemId: i.id, quantity: row.returnQty, condition: row.condition as ItemCondition, note: row.note || undefined });
      if (mq > 0) lines.push({ itemId: i.id, quantity: row.missingQty, condition: 'MISSING', note: row.note || undefined });
    }
    onSubmit({ note: note || undefined, lines });
  }

  if (returnable.length === 0) {
    return <p className="muted">Everything has been returned — nothing outstanding.</p>;
  }

  return (
    <form onSubmit={submit} className="stack">
      {(error || localError) && <div className="notice notice-error">{error || localError}</div>}
      {prefill && (
        <div className="notice notice-info">
          Return qty was prefilled from your imported file. Review it, then set Condition, Missing and notes as needed.
        </div>
      )}
      <p className="muted small" style={{ margin: 0 }}>
        <strong>Return qty</strong> records units coming back now (returning a missing item brings it back —
        any you don't record stays missing). <strong>Missing</strong> marks still-outstanding units as lost.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>
                <input ref={masterRef} type="checkbox" style={{ width: 'auto' }} checked={allIncluded}
                       title="Select all" aria-label="Select all items"
                       onChange={(e) => setAll(e.target.checked)} />
              </th>
              <th>Item</th><th className="num">Remaining</th>
              <th className="num">Return qty</th><th>Condition</th>
              <th className="num">Missing</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {returnable.map((i) => {
              const row = rows[i.id];
              const canMarkMissing = rem(i) > 0;
              return (
                <tr key={i.id}>
                  <td>
                    <input type="checkbox" checked={row.include} style={{ width: 'auto' }}
                           aria-label={`Select ${i.name}`}
                           onChange={(e) => patch(i.id, { include: e.target.checked })} />
                  </td>
                  <td>
                    <strong>{i.name}</strong>{i.unit && <span className="muted small"> ({i.unit})</span>}
                    {miss(i) > 0 && <div className="small muted">{qty(i.missing)} missing</div>}
                  </td>
                  <td className="num">{qty(i.remaining)}</td>
                  <td className="num">
                    <input type="number" min="0" step="0.001" max={owed(i)} value={row.returnQty}
                           disabled={!row.include} style={{ width: 88 }}
                           title={`Up to ${qty(String(owed(i)))} can come back`}
                           onChange={(e) => patch(i.id, { returnQty: e.target.value })} />
                  </td>
                  <td>
                    <select value={row.condition} disabled={!row.include}
                            onChange={(e) => patch(i.id, { condition: e.target.value as ReturnedCondition })}>
                      {RETURNED_CONDITIONS.map((c) => <option key={c} value={c}>{LABEL[c]}</option>)}
                    </select>
                  </td>
                  <td className="num">
                    {canMarkMissing ? (
                      <input type="number" min="0" step="0.001" max={i.remaining} value={row.missingQty}
                             disabled={!row.include} style={{ width: 78 }}
                             title={`Up to ${qty(i.remaining)} outstanding`}
                             onChange={(e) => patch(i.id, { missingQty: e.target.value })} />
                    ) : (
                      // Missing-only item: nothing new to mark lost — the un-returned part just
                      // stays missing, shown live as the owner types a return qty.
                      <span className="muted small" title="Auto-calculated: missing minus what you return now">
                        {(() => {
                          const left = Math.max(0, miss(i) - (Number(row.returnQty) || 0));
                          return left === 0 ? 'None' : `${qty(String(left))} still missing`;
                        })()}
                      </span>
                    )}
                  </td>
                  <td>
                    <input value={row.note} disabled={!row.include} placeholder="optional"
                           onChange={(e) => patch(i.id, { note: e.target.value })} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="field">
        <label>Return note <span className="muted">(optional)</span></label>
        <input value={note} onChange={(e) => setNote(e.target.value)}
               placeholder="e.g. Remaining items will be returned tomorrow" />
      </div>
      <div className="row">
        <button className="btn btn-primary" disabled={busy}>{busy ? 'Recording…' : 'Record return'}</button>
        {onCancel && <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}
