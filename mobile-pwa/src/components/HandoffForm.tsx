import { useRef, useState } from 'react';
import type { CreateHandoffInput, ItemCondition, ItemInput } from '../api/types';
import { localPartsToIso } from '../lib/format';
import { INVALID_EMAIL_MESSAGE, isValidEmail } from '../lib/validation';

const CONDITIONS: ItemCondition[] = ['GOOD', 'DAMAGED', 'OTHER'];
const CATEGORY_SUGGESTIONS = [
  'EVENT', 'CONSTRUCTION', 'IT_EQUIPMENT', 'EDUCATION', 'RENTAL', 'OFFICE_ASSET',
  'DOCUMENT', 'KEYS_ACCESS', 'REPAIR_SERVICE', 'OTHER',
];

interface ItemRow extends ItemInput {
  key: number;
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
  dueDate: string;   // yyyy-mm-dd
  dueTime: string;   // HH:mm
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

/** What the form sends: the handoff's details and its items, cleaned the way the desktop form cleans them. */
export interface HandoffFormSubmit {
  header: Omit<CreateHandoffInput, 'items'>;
  items: ItemInput[];
}

/** Which button was pressed: only save the draft, or save it and send the recipient their link. */
export type SubmitMode = 'draft' | 'send';

/**
 * The handoff form for the phone: details, who it is for, and the items as cards (name, quantity, unit; the rest under "More").
 * It only gathers and checks the obvious (required fields, a quantity above zero); the backend validates everything again and
 * decides whether the account may create or send.
 */
export function HandoffForm({ initial, onSubmit, busy, error, canSend, saveLabel = 'Save draft' }: {
  initial: HandoffFormValue;
  onSubmit: (value: HandoffFormSubmit, mode: SubmitMode) => void;
  busy?: boolean;
  error?: string | null;
  /** Offer "Save & send link" next to saving the draft. */
  canSend?: boolean;
  saveLabel?: string;
}) {
  const nextKey = useRef(initial.items.length);
  const [v, setV] = useState<Omit<HandoffFormValue, 'items'>>(() => {
    const { items: _items, ...rest } = initial;
    void _items;
    return rest;
  });
  const [items, setItems] = useState<ItemRow[]>(() => initial.items.map((it, i) => ({ ...it, key: i })));
  const [localError, setLocalError] = useState<string | null>(null);
  const [emailError, setEmailError] = useState<string | null>(null);

  const set = <K extends keyof typeof v>(k: K) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setV({ ...v, [k]: e.target.value });
  const setItem = (key: number, patch: Partial<ItemInput>) =>
    setItems((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const addItem = () => setItems((rows) => [...rows, { ...EMPTY_ITEM, key: nextKey.current++ }]);
  const removeItem = (key: number) => setItems((rows) => rows.filter((r) => r.key !== key));

  function submit(mode: SubmitMode) {
    setLocalError(null);
    if (!isValidEmail(v.recipientEmail.trim())) {   // the browser's own check lets name@ee through; the backend refuses it too
      setEmailError(INVALID_EMAIL_MESSAGE);
      document.getElementById('recipient-email')?.focus();
      return;
    }
    const cleaned: ItemInput[] = items
      .filter((it) => it.name.trim() !== '')
      .map((it) => ({
        name: it.name.trim(),
        quantity: it.quantity || '0',
        unit: it.unit?.trim() || undefined,
        condition: it.condition || 'GOOD',
        serialNumber: it.serialNumber?.trim() || undefined,
        notes: it.notes?.trim() || undefined,
      }));
    if (cleaned.length === 0) { setLocalError('Add at least one item, with a name.'); return; }
    const bad = cleaned.find((it) => !(Number(it.quantity) > 0));
    if (bad) { setLocalError(`Enter a quantity above zero for “${bad.name}”.`); return; }
    onSubmit({
      header: {
        title: v.title.trim(),
        category: v.category.trim() || undefined,
        purpose: v.purpose.trim() || undefined,
        senderName: v.senderName.trim(),
        senderOrganization: v.senderOrganization.trim() || undefined,
        recipientName: v.recipientName.trim(),
        recipientEmail: v.recipientEmail.trim(),
        recipientPhone: v.recipientPhone.trim() || undefined,
        dueAt: localPartsToIso(v.dueDate, v.dueTime),
      },
      items: cleaned,
    }, mode);
  }

  // The browser checks the required fields and the email shape; the button that was pressed says what to do next.
  const pressed = useRef<SubmitMode>('draft');
  function onFormSubmit(e: React.FormEvent) {
    e.preventDefault();
    submit(pressed.current);
  }

  const shownError = error ?? localError;
  return (
    <form onSubmit={onFormSubmit} className="stack">
      {shownError && <div className="notice notice-error" role="alert">{shownError}</div>}

      <section className="card">
        <h2>Handoff</h2>
        <div className="field">
          <label htmlFor="title">Title</label>
          <input id="title" value={v.title} onChange={set('title')} required maxLength={200} placeholder="e.g. Wedding rentals for Saturday" />
        </div>
        <div className="two">
          <div className="field">
            <label htmlFor="due-date">Expected return date <span className="muted">(optional)</span></label>
            <input id="due-date" type="date" value={v.dueDate} onChange={set('dueDate')} aria-describedby="due-hint" />
          </div>
          <div className="field">
            <label htmlFor="due-time">Expected return time <span className="muted">(optional)</span></label>
            <input id="due-time" type="time" value={v.dueTime} disabled={!v.dueDate} onChange={set('dueTime')} aria-describedby="due-hint" />
          </div>
        </div>
        <p id="due-hint" className="hint">
          Your local time. {v.dueDate ? 'Leave the time empty to count the return as due from the start of that day.' : 'Pick a date first to add a time.'}
        </p>
      </section>

      <section className="card">
        <h2>Who it is for</h2>
        <div className="field">
          <label htmlFor="recipient-name">Recipient name</label>
          <input id="recipient-name" value={v.recipientName} onChange={set('recipientName')} required maxLength={200} autoComplete="off" />
        </div>
        <div className="field">
          <label htmlFor="recipient-email">Recipient email</label>
          <input id="recipient-email" type="email" inputMode="email" autoCapitalize="none" value={v.recipientEmail}
                 onChange={(e) => { set('recipientEmail')(e); setEmailError(null); }} required autoComplete="off"
                 aria-invalid={emailError !== null} aria-describedby={emailError ? 'recipient-email-error' : undefined} />
          {emailError && <div id="recipient-email-error" className="form-error" role="alert">{emailError}</div>}
        </div>
        <div className="field">
          <label htmlFor="sender-name">Your name (sender)</label>
          <input id="sender-name" value={v.senderName} onChange={set('senderName')} required maxLength={200} />
        </div>
      </section>

      <section className="card">
        <div className="spread"><h2>Items</h2><span className="muted small">{items.length} {items.length === 1 ? 'item' : 'items'}</span></div>
        <ul className="stack item-list">
          {items.map((it, n) => (
            <li key={it.key} className="item-card">
              <div className="field">
                <label htmlFor={`item-name-${it.key}`}>Item {n + 1}</label>
                <input id={`item-name-${it.key}`} value={it.name} placeholder="e.g. Chairs"
                       onChange={(e) => setItem(it.key, { name: e.target.value })} />
              </div>
              <div className="two">
                <div className="field">
                  <label htmlFor={`item-qty-${it.key}`}>Quantity</label>
                  <input id={`item-qty-${it.key}`} type="number" inputMode="decimal" min="0" step="0.001" value={it.quantity}
                         onChange={(e) => setItem(it.key, { quantity: e.target.value })} />
                </div>
                <div className="field">
                  <label htmlFor={`item-unit-${it.key}`}>Unit</label>
                  <input id={`item-unit-${it.key}`} value={it.unit ?? ''} placeholder="pcs, kg…" onChange={(e) => setItem(it.key, { unit: e.target.value })} />
                </div>
              </div>
              <details className="more">
                <summary>More (condition, ID, notes)</summary>
                <div className="field">
                  <label htmlFor={`item-cond-${it.key}`}>Condition</label>
                  <select id={`item-cond-${it.key}`} value={it.condition ?? 'GOOD'}
                          onChange={(e) => setItem(it.key, { condition: e.target.value as ItemCondition })}>
                    {CONDITIONS.map((c) => <option key={c} value={c}>{c[0] + c.slice(1).toLowerCase()}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label htmlFor={`item-serial-${it.key}`}>Serial / asset ID</label>
                  <input id={`item-serial-${it.key}`} value={it.serialNumber ?? ''} onChange={(e) => setItem(it.key, { serialNumber: e.target.value })} />
                </div>
                <div className="field">
                  <label htmlFor={`item-notes-${it.key}`}>Notes</label>
                  <input id={`item-notes-${it.key}`} value={it.notes ?? ''} onChange={(e) => setItem(it.key, { notes: e.target.value })} />
                </div>
              </details>
              <button type="button" className="btn btn-sm btn-negative" disabled={items.length === 1} onClick={() => removeItem(it.key)}>
                Remove item {n + 1}
              </button>
            </li>
          ))}
        </ul>
        <button type="button" className="btn btn-block" onClick={addItem}>+ Add item</button>
      </section>

      <details className="card more">
        <summary>More details (optional)</summary>
        <div className="field">
          <label htmlFor="purpose">Purpose / notes</label>
          <textarea id="purpose" rows={2} value={v.purpose} onChange={set('purpose')} maxLength={2000} />
        </div>
        <div className="field">
          <label htmlFor="category">Category</label>
          <input id="category" list="categories" value={v.category} onChange={set('category')} placeholder="e.g. EVENT" />
          <datalist id="categories">{CATEGORY_SUGGESTIONS.map((c) => <option key={c} value={c} />)}</datalist>
        </div>
        <div className="field">
          <label htmlFor="sender-org">Your organization</label>
          <input id="sender-org" value={v.senderOrganization} onChange={set('senderOrganization')} />
        </div>
        <div className="field">
          <label htmlFor="recipient-phone">Recipient phone</label>
          <input id="recipient-phone" type="tel" inputMode="tel" value={v.recipientPhone} onChange={set('recipientPhone')} maxLength={40} />
        </div>
      </details>

      <div className="action-bar">
        <button className={`btn ${canSend ? '' : 'btn-primary'}`} disabled={busy} onClick={() => { pressed.current = 'draft'; }}>
          {busy ? 'Saving…' : saveLabel}
        </button>
        {canSend && (
          <button className="btn btn-primary" disabled={busy} onClick={() => { pressed.current = 'send'; }}>
            Save & send link
          </button>
        )}
      </div>
    </form>
  );
}
