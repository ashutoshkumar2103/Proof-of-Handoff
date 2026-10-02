import { useState } from 'react';
import type { CreateHandoffInput, ItemCondition, ItemInput } from '../api/types';
import { localPartsToIso } from '../lib/format';
import { ItemImportPanel } from './ItemImportPanel';
import type { ImportedItem } from './ItemImportPanel';
import { TimeSelect } from './TimeSelect';

const CONDITIONS: ItemCondition[] = ['GOOD', 'DAMAGED', 'MISSING', 'OTHER'];

/** Opens the native date/time picker when the field itself is clicked (not just the icon). */
function openPicker(e: React.MouseEvent<HTMLInputElement>) {
  const el = e.currentTarget as HTMLInputElement & { showPicker?: () => void };
  try { el.showPicker?.(); } catch { /* showPicker may be unsupported or blocked; ignore */ }
}

export interface HandoffFormValue {
  title: string;
  category: string;
  purpose: string;
  senderName: string;
  senderOrganization: string;
  recipientName: string;
  recipientEmail: string;
  recipientPhone: string;
  dueDate: string; // yyyy-mm-dd
  dueTime: string; // HH:mm
  items: ItemInput[];
}

const EMPTY_ITEM: ItemInput = { name: '', quantity: '1', unit: '', condition: 'GOOD', serialNumber: '', notes: '' };

export function emptyFormValue(partial?: Partial<HandoffFormValue>): HandoffFormValue {
  return {
    title: '', category: '', purpose: '', senderName: '', senderOrganization: '',
    recipientName: '', recipientEmail: '', recipientPhone: '', dueDate: '', dueTime: '',
    items: [{ ...EMPTY_ITEM }],
    ...partial,
  };
}

const CATEGORY_SUGGESTIONS = [
  'EVENT', 'CONSTRUCTION', 'IT_EQUIPMENT', 'EDUCATION', 'RENTAL', 'OFFICE_ASSET',
  'DOCUMENT', 'KEYS_ACCESS', 'REPAIR_SERVICE', 'OTHER',
];

export function HandoffForm({ initial, submitLabel, onSubmit, busy, error }: {
  initial: HandoffFormValue;
  submitLabel: string;
  onSubmit: (value: { header: Omit<CreateHandoffInput, 'items'>; items: ItemInput[] }) => void;
  busy?: boolean;
  error?: string | null;
}) {
  const [v, setV] = useState<HandoffFormValue>(initial);
  const [importOpen, setImportOpen] = useState(false);
  const [importNotice, setImportNotice] = useState<string | null>(null);
  // Which item rows have the optional "serial / ID" field revealed.
  const [showIdFor, setShowIdFor] = useState<Set<number>>(
    () => new Set(initial.items.map((it, i) => (it.serialNumber ? i : -1)).filter((i) => i >= 0)));

  const set = <K extends keyof HandoffFormValue>(k: K, val: HandoffFormValue[K]) => setV({ ...v, [k]: val });

  const setItem = (i: number, patch: Partial<ItemInput>) =>
    setV({ ...v, items: v.items.map((it, idx) => (idx === i ? { ...it, ...patch } : it)) });
  const addItem = () => setV({ ...v, items: [...v.items, { ...EMPTY_ITEM }] });
  const removeItem = (i: number) => setV({ ...v, items: v.items.filter((_, idx) => idx !== i) });
  // Items read from a file go into the same table, as if typed: a table with only blank rows is filled, otherwise appended.
  const addImported = (imported: ImportedItem[], fileName: string) => {
    const typed = v.items.filter((it) => it.name.trim() !== '');
    setV({
      ...v,
      items: [...typed, ...imported.map((i): ItemInput =>
        ({ name: i.name, quantity: i.quantity, unit: '', condition: 'GOOD', serialNumber: '', notes: '' }))],
    });
    setImportOpen(false);
    setImportNotice(`Added ${imported.length} item${imported.length === 1 ? '' : 's'} from ${fileName}. Check them below, then create the draft.`);
  };
  const toggleId = (i: number) => setShowIdFor((s) => {
    const next = new Set(s);
    if (next.has(i)) next.delete(i); else next.add(i);
    return next;
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const items: ItemInput[] = v.items
      .filter((it) => it.name.trim() !== '')
      .map((it) => ({
        name: it.name.trim(),
        quantity: it.quantity || '0',
        unit: it.unit || undefined,
        condition: it.condition || 'GOOD',
        serialNumber: it.serialNumber || undefined,
        notes: it.notes || undefined,
      }));
    onSubmit({
      header: {
        title: v.title.trim(),
        category: v.category || undefined,
        purpose: v.purpose || undefined,
        senderName: v.senderName.trim(),
        senderOrganization: v.senderOrganization || undefined,
        recipientName: v.recipientName.trim(),
        recipientEmail: v.recipientEmail.trim(),
        recipientPhone: v.recipientPhone || undefined,
        dueAt: localPartsToIso(v.dueDate, v.dueTime),
      },
      items,
    });
  }

  return (
    <form onSubmit={submit} className="stack">
      {error && <div className="notice notice-error">{error}</div>}

      <div className="card">
        <h2>Handoff details</h2>
        <div className="field-row">
          <div className="field">
            <label>Title</label>
            <input value={v.title} onChange={(e) => set('title', e.target.value)} required maxLength={200}
                   placeholder="e.g. Wedding rentals for Saturday event" />
          </div>
          <div className="field">
            <label>Category</label>
            <input list="categories" value={v.category} onChange={(e) => set('category', e.target.value)}
                   placeholder="e.g. EVENT" />
            <datalist id="categories">
              {CATEGORY_SUGGESTIONS.map((c) => <option key={c} value={c} />)}
            </datalist>
          </div>
        </div>
        <div className="detail-row">
          <div className="field">
            <label>Due date <span className="muted">(optional)</span></label>
            <input type="date" aria-label="Due date" value={v.dueDate}
                   onClick={openPicker} onChange={(e) => set('dueDate', e.target.value)} />
          </div>
          <div className="field">
            <label>Time</label>
            <TimeSelect value={v.dueTime} disabled={!v.dueDate} onChange={(val) => set('dueTime', val)} />
          </div>
          <div className="field">
            <label>Purpose / notes <span className="muted">(optional)</span></label>
            <input value={v.purpose} onChange={(e) => set('purpose', e.target.value)} maxLength={2000}
                   placeholder="Short note about this handoff" />
          </div>
        </div>
      </div>

      <div className="card">
        <h2>Parties</h2>
        <div className="field-row">
          <div className="field">
            <label>Sender name</label>
            <input value={v.senderName} onChange={(e) => set('senderName', e.target.value)} required maxLength={200} />
          </div>
          <div className="field">
            <label>Sender organization <span className="muted">(optional)</span></label>
            <input value={v.senderOrganization} onChange={(e) => set('senderOrganization', e.target.value)} />
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label>Recipient name</label>
            <input value={v.recipientName} onChange={(e) => set('recipientName', e.target.value)} required maxLength={200} />
          </div>
          <div className="field">
            <label>Recipient email</label>
            <input type="email" value={v.recipientEmail} onChange={(e) => set('recipientEmail', e.target.value)} required />
          </div>
        </div>
        <div className="field">
          <label>Recipient phone <span className="muted">(optional)</span></label>
          <input value={v.recipientPhone} onChange={(e) => set('recipientPhone', e.target.value)} maxLength={40} />
        </div>
      </div>

      <div className="card">
        <h2>Items handed over</h2>
        <p className="muted small">
          Just the item, quantity and condition — no SKU/serial needed for bulk items.
          Add an ID only if you want to track one specific unit (e.g. a drill).
        </p>

        <div className="item-head">
          <span>Item name</span>
          <span>Quantity</span>
          <span>Unit</span>
          <span>Condition</span>
          <span>Notes</span>
          <span></span>
        </div>

        <div className="stack">
          {v.items.map((it, i) => (
            <div key={i} className="item">
              <div className="item-grid">
                <input aria-label="Item name" value={it.name} placeholder="e.g. Chairs"
                       onChange={(e) => setItem(i, { name: e.target.value })} />
                <input aria-label="Quantity" type="number" min="0" step="0.001" placeholder="Qty"
                       value={it.quantity} onChange={(e) => setItem(i, { quantity: e.target.value })} />
                <input aria-label="Unit" value={it.unit} placeholder="pcs, kg…"
                       onChange={(e) => setItem(i, { unit: e.target.value })} />
                <select aria-label="Condition" value={it.condition ?? 'GOOD'}
                        onChange={(e) => setItem(i, { condition: e.target.value as ItemCondition })}>
                  {CONDITIONS.map((c) => <option key={c} value={c}>{c[0] + c.slice(1).toLowerCase()}</option>)}
                </select>
                <input aria-label="Notes" value={it.notes} placeholder="e.g. 50 back Friday"
                       onChange={(e) => setItem(i, { notes: e.target.value })} />
                <button type="button" className="btn btn-sm btn-ghost icon-btn" title="Remove item"
                        disabled={v.items.length === 1} onClick={() => removeItem(i)}>✕</button>
              </div>
              <div className="item-sub">
                {showIdFor.has(i) ? (
                  <div className="row">
                    <input aria-label="Serial or ID" value={it.serialNumber} placeholder="Serial / asset ID"
                           style={{ maxWidth: 280 }} onChange={(e) => setItem(i, { serialNumber: e.target.value })} />
                    <button type="button" className="btn btn-sm btn-ghost"
                            onClick={() => { setItem(i, { serialNumber: '' }); toggleId(i); }}>Remove ID</button>
                  </div>
                ) : (
                  <button type="button" className="btn btn-sm btn-ghost" onClick={() => toggleId(i)}>
                    + Add serial / ID (optional)
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
        <div className="row mt-2">
          <button type="button" className="btn" style={{ flex: 1 }} onClick={addItem}>+ Add item manually</button>
          <button type="button" className="btn" style={{ flex: 1 }} onClick={() => { setImportOpen(true); setImportNotice(null); }}>
            Import items from file
          </button>
        </div>
        {importNotice && <div className="notice notice-success mt-2" role="status">{importNotice}</div>}
        {importOpen && <ItemImportPanel onAdd={addImported} onClose={() => setImportOpen(false)} />}
      </div>

      <div className="row">
        <button className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : submitLabel}</button>
      </div>
    </form>
  );
}
