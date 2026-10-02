import type { HandoffItem } from '../api/types';
import { CONDITION_LABELS, qty } from '../lib/format';

/**
 * The core Outgoing / Returned / Remaining table — shows all three together so the
 * outstanding amount is always visible at a glance. Remaining is always computed by the
 * backend (never entered by hand).
 *
 * `compact` is for a narrow page (the recipient's): the quantity moves next to the item, the unit joins the
 * quantity, the notes go under the name, and the Unit / Condition / Notes columns are dropped. `showProgress`
 * hides Returned / Missing / Remaining while there is nothing to show yet. The defaults draw the full table.
 */
export function ItemsTable({ items, showPending = true, compact = false, showProgress = true }:
  { items: HandoffItem[]; showPending?: boolean; compact?: boolean; showProgress?: boolean }) {
  const hasPending = showPending && items.some((i) => Number(i.returnedPending) > 0);
  return (
    <div className={`table-wrap${compact ? ' items-compact' : ''}`}>
      <table>
        <thead>
          <tr>
            <th>Item</th>
            <th className="num">{compact ? 'Quantity' : 'Outgoing'}</th>
            {showProgress && <th className="num">Returned</th>}
            {showProgress && hasPending && <th className="num">Pending</th>}
            {showProgress && <th className="num">Missing</th>}
            {showProgress && <th className="num">Remaining</th>}
            {!compact && <th>Unit</th>}
            {!compact && <th>Condition</th>}
            {!compact && <th>Notes</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((it) => {
            const remaining = Number(it.remaining);
            return (
              <tr key={it.id}>
                <td>
                  <strong>{it.name}</strong>
                  {it.serialNumber && <div className="small muted">ID: {it.serialNumber}</div>}
                  {compact && it.notes && <div className="small muted">{it.notes}</div>}
                </td>
                <td className="num">{qty(it.outgoing)}{compact && it.unit && <span className="small muted"> {it.unit}</span>}</td>
                {showProgress && <td className="num">{qty(it.returnedConfirmed)}</td>}
                {showProgress && hasPending && <td className="num muted">{qty(it.returnedPending)}</td>}
                {showProgress && <td className={`num ${Number(it.missing) > 0 ? 'remaining-open' : 'muted'}`}>{qty(it.missing)}</td>}
                {showProgress && <td className={`num ${remaining > 0 ? 'remaining-open' : 'remaining-zero'}`}>{qty(it.remaining)}</td>}
                {!compact && <td className="small muted">{it.unit || '—'}</td>}
                {!compact && <td className="small muted">{it.condition ? (CONDITION_LABELS[it.condition] ?? it.condition) : '—'}</td>}
                {!compact && <td className="small muted">{it.notes || '—'}</td>}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
