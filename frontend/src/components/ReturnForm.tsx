import { Fragment, useEffect, useRef, useState } from 'react';
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

const milli = (n: number) => Math.round(n * 1000);   // quantities have up to three decimals: compared as whole thousandths, so 0.1 + 0.2 is 0.3

/**
 * What a row's quantities come to, worked out the way the backend (ReturnService) judges them, for the live "Still with recipient" and for
 * the check on submit — one function, so what is shown and what is refused cannot differ. Returns clear outstanding units first, so what
 * is still with the recipient is Remaining − Return qty − Missing; only the units beyond Remaining bring back previously-missing ones
 * (up to everything not yet returned), and Missing can only mark what would still be outstanding. A lower Return qty never marks anything missing.
 */
function figures(i: HandoffItem, row: RowState): { still: number; problem: string | null } {
  const rq = Number(row.returnQty) || 0;
  const mq = Number(row.missingQty) || 0;
  const outstandingLeft = Math.max(0, milli(rem(i)) - milli(rq)) / 1000;
  const still = (milli(outstandingLeft) - milli(mq)) / 1000;
  let problem: string | null = null;
  if (rq < 0) problem = 'Return qty can’t be negative.';
  else if (mq < 0) problem = 'Missing can’t be negative.';
  else if (milli(rq) > milli(owed(i))) problem = `Return qty is more than is still owed (${qty(String(owed(i)))}).`;
  else if (milli(mq) > milli(outstandingLeft)) problem = `Return qty + Missing is more than Remaining (${qty(String(rem(i)))}).`;
  return { still, problem };
}

/**
 * Records a return with just two quantities per item:
 *  • Return qty (+ Condition) — units coming back now. They clear outstanding units first;
 *    anything beyond that brings previously-missing units back, so returning some of a
 *    missing item leaves the rest missing.
 *  • Missing — mark still-outstanding units as lost.
 * "Still with recipient" is worked out from the two as they are typed (see {@link figures}); neither is ever filled in from the other.
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

  const invalid = returnable.some((i) => rows[i.id]?.include && figures(i, rows[i.id]).problem);   // the button waits until every ticked row is right
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
      const { problem } = figures(i, row);
      if (problem) {
        setLocalError(`"${i.name}": ${problem}`);
        return;
      }
      if (rq <= 0 && mq <= 0) {
        setLocalError(`Enter a return or missing quantity for "${i.name}".`);
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
        any you don't record stays missing). <strong>Missing</strong> marks still-outstanding units as lost.{' '}
        <strong>Still with recipient</strong> is Remaining − Return qty − Missing: units you record neither way stay with the recipient, they are not marked missing.
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
              <th className="num still-col">Still with recipient</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {returnable.map((i) => {
              const row = rows[i.id];
              const canMarkMissing = rem(i) > 0;
              const { still, problem } = figures(i, row);
              const shown = row.include && problem;   // only a row that is going to be recorded is checked
              // A row that is not ticked records nothing, so everything outstanding stays with the recipient; one with a problem has no figure yet.
              const stillText = !row.include ? qty(i.remaining) : problem ? '—' : qty(String(still));
              return (
                <Fragment key={i.id}>
                <tr>
                  <td>
                    <input type="checkbox" checked={row.include} style={{ width: 'auto' }}
                           aria-label={`Select ${i.name}`}
                           onChange={(e) => patch(i.id, { include: e.target.checked })} />
                  </td>
                  <td>
                    <strong>{i.name}</strong>{i.unit && <span className="muted small"> ({i.unit})</span>}
                    {miss(i) > 0 && <div className="small muted">{qty(i.missing)} missing</div>}
                    {/* on a phone the column below is off to the right of the scrolling table: the same figure is shown here instead */}
                    <div className="small still-inline">Still with recipient: <strong>{stillText}</strong></div>
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
                  <td className="num still-col" aria-live="polite">
                    {!row.include ? <span className="muted">{stillText}</span> : problem ? <span className="muted" title="Fix the quantities to see it">{stillText}</span>
                      : <strong>{stillText}</strong>}
                  </td>
                  <td>
                    <input value={row.note} disabled={!row.include} placeholder="optional"
                           onChange={(e) => patch(i.id, { note: e.target.value })} />
                  </td>
                </tr>
                {shown && (
                  <tr>
                    <td />
                    <td colSpan={7} style={{ paddingTop: 0 }}>
                      {/* sticky and no wider than the screen: in the scrolling table on a phone the message stays in view and wraps */}
                      <div className="small" role="alert" style={{ position: 'sticky', left: 0, width: 'max-content', maxWidth: 'calc(100vw - 7rem)', whiteSpace: 'normal', color: 'var(--danger)' }}>{problem}</div>
                    </td>
                  </tr>
                )}
                </Fragment>
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
        <button className="btn btn-primary" disabled={busy || invalid}
                title={invalid ? 'Fix the highlighted quantities first' : undefined}>{busy ? 'Recording…' : 'Record return'}</button>
        {onCancel && <button type="button" className="btn btn-negative" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}
