import { useState } from 'react';
import { isSubscriptionEnded } from '../api/client';
import { documentCheckApi } from '../api/endpoints';
import { errorMessage, useSubscriptionEndedModal } from './ui';

export interface ImportedItem {
  name: string;
  quantity: string;
}

interface Row extends ImportedItem {
  duplicate: boolean;
}

/**
 * Importing an item list (CSV or Excel) into a handoff being drafted: pick a file, review what was read (fix a name
 * or quantity, drop a row), then add it to the SAME item table the form already has. Nothing is saved or created
 * here — the handoff is saved by the form's own button, exactly as if the items had been typed. The item and
 * quantity columns are found automatically (by headers like Item / Name / Description and Qty / Quantity / Count),
 * the same way HandoffCheck reads a file.
 */
export function ItemImportPanel({ onAdd, onClose }: { onAdd: (items: ImportedItem[], fileName: string) => void; onClose: () => void }) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [fileName, setFileName] = useState('');
  const [skipped, setSkipped] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const showSubscriptionEnded = useSubscriptionEndedModal();   // importing is HandoffCheck, which needs an active subscription

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';   // choosing the same file again must read it again
    if (!file) return;
    setError(null);
    setBusy(true);
    try {
      const preview = await documentCheckApi.importItems(file);
      setRows(preview.lines.map((l) => ({ name: l.name, quantity: String(l.quantity), duplicate: l.duplicate })));
      setFileName(preview.fileName ?? file.name);
      setSkipped(preview.skippedRows);
    } catch (err) {
      setRows(null);
      if (isSubscriptionEnded(err)) void showSubscriptionEnded();
      else setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const patch = (i: number, p: Partial<ImportedItem>) =>
    setRows((rs) => rs && rs.map((r, idx) => (idx === i ? { ...r, ...p } : r)));
  const remove = (i: number) => setRows((rs) => rs && rs.filter((_, idx) => idx !== i));

  // The names that now appear more than once (after any edits), so the flag stays true to what is on screen.
  const counts = new Map<string, number>();
  (rows ?? []).forEach((r) => counts.set(r.name.trim().toLowerCase(), (counts.get(r.name.trim().toLowerCase()) ?? 0) + 1));
  const invalid = (r: Row) => r.name.trim() === '' || !(Number(r.quantity) > 0);
  const anyInvalid = (rows ?? []).some(invalid);

  return (
    <div className="panel mt-2" aria-label="Import items from a file">
      <div className="spread">
        <h3 style={{ margin: 0 }}>Import items from a file</h3>
        <button type="button" className="btn btn-sm btn-ghost" onClick={onClose}>Close</button>
      </div>
      <p className="small muted">
        Upload a <strong>.csv</strong> or <strong>.xlsx</strong> file with a column for the item (named Item, Name,
        Description…) and a column for the quantity (Qty, Quantity, Count…). You can check and correct everything
        before it is added; nothing is saved until you create the draft.
      </p>
      {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      <input type="file" accept=".csv,.xlsx" aria-label="Choose an item list file" disabled={busy} onChange={onFile} />
      {busy && <p className="small muted mt-1">Reading the file…</p>}

      {rows && (
        <>
          <p className="small mt-2">
            Read <strong>{rows.length}</strong> item{rows.length === 1 ? '' : 's'} from <em>{fileName}</em>.
            {skipped > 0 && (
              <span className="remaining-open"> {skipped} row{skipped === 1 ? ' was' : 's were'} left out because
                they had no usable item and quantity (a quantity must be a number above zero, up to 3 decimals).</span>
            )}
          </p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Item</th><th className="num">Quantity</th><th></th></tr></thead>
              <tbody>
                {rows.map((r, i) => {
                  const repeated = (counts.get(r.name.trim().toLowerCase()) ?? 0) > 1;
                  return (
                    <tr key={i}>
                      <td>
                        <input aria-label={`Item name, row ${i + 1}`} value={r.name} maxLength={300}
                               onChange={(e) => patch(i, { name: e.target.value })} />
                        {repeated && r.name.trim() !== '' && (
                          <div className="small remaining-open">This name appears more than once. They are added as separate lines.</div>
                        )}
                      </td>
                      <td className="num">
                        <input aria-label={`Quantity, row ${i + 1}`} type="number" min="0" step="0.001" value={r.quantity}
                               onChange={(e) => patch(i, { quantity: e.target.value })} style={{ maxWidth: 120 }} />
                      </td>
                      <td>
                        <button type="button" className="btn btn-sm btn-ghost icon-btn" title="Leave this row out"
                                onClick={() => remove(i)}>✕</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {anyInvalid && <p className="small remaining-open">Every row needs a name and a quantity above zero.</p>}
          <div className="row mt-2">
            <button type="button" className="btn btn-primary" disabled={rows.length === 0 || anyInvalid}
                    onClick={() => onAdd(rows.map(({ name, quantity }) => ({ name: name.trim(), quantity })), fileName)}>
              Add {rows.length} item{rows.length === 1 ? '' : 's'} to this handoff
            </button>
            <button type="button" className="btn btn-ghost" onClick={() => { setRows(null); setError(null); }}>Choose another file</button>
          </div>
        </>
      )}
    </div>
  );
}
