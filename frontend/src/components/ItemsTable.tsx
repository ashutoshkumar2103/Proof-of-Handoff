import type { HandoffItem } from '../api/types';
import { CONDITION_LABELS, qty } from '../lib/format';

/**
 * The core Outgoing / Returned / Remaining table — shows all three together so the
 * outstanding amount is always visible at a glance. Remaining is always computed by the
 * backend (never entered by hand).
 */
export function ItemsTable({ items, showPending = true }: { items: HandoffItem[]; showPending?: boolean }) {
  const hasPending = showPending && items.some((i) => Number(i.returnedPending) > 0);
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Item</th>
            <th className="num">Outgoing</th>
            <th className="num">Returned</th>
            {hasPending && <th className="num">Pending</th>}
            <th className="num">Missing</th>
            <th className="num">Remaining</th>
            <th>Unit</th>
            <th>Condition</th>
            <th>Notes</th>
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
                </td>
                <td className="num">{qty(it.outgoing)}</td>
                <td className="num">{qty(it.returnedConfirmed)}</td>
                {hasPending && <td className="num muted">{qty(it.returnedPending)}</td>}
                <td className={`num ${Number(it.missing) > 0 ? 'remaining-open' : 'muted'}`}>{qty(it.missing)}</td>
                <td className={`num ${remaining > 0 ? 'remaining-open' : 'remaining-zero'}`}>{qty(it.remaining)}</td>
                <td className="small muted">{it.unit || '—'}</td>
                <td className="small muted">{it.condition ? (CONDITION_LABELS[it.condition] ?? it.condition) : '—'}</td>
                <td className="small muted">{it.notes || '—'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
